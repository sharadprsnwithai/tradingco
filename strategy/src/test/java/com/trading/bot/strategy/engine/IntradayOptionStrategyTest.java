package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.StrategyExecutionGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.TargetIndex;
import com.trading.bot.strategy.model.TradeLeg;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
class IntradayOptionStrategyTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final StrategyProperties props =
      new StrategyProperties(
          ExecutionMode.PAPER,
          2,
          30.0,
          true,
          LocalTime.of(9, 45),
          LocalTime.of(10, 0),
          LocalTime.of(15, 0),
          1000,
          2000);

  private Quote makeQuote(InstrumentToken token, String symbol, BigDecimal price) {
    return new Quote(
        token,
        symbol,
        price,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        1000L);
  }

  @Test
  void mondayHedgeBuysOtmWingsAtNineFortyFiveAndSavesHedgedState() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    // Monday 09:45 IST
    final Instant mondayNineFortyFive = Instant.parse("2026-10-26T04:15:00Z");
    final Clock clock = Clock.fixed(mondayNineFortyFive, IST);

    final InstrumentToken spotToken = new InstrumentToken(256265L);
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(spotToken, makeQuote(spotToken, "NIFTY 50", BigDecimal.valueOf(25000.0))));

    when(gateway.executeBuyMarket(any(), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(3.50));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeHedge();

    // 2 lots * 65 units = 130 qty
    verify(gateway, times(2)).executeBuyMarket(any(), any(), eq(Exchange.NFO), eq(130));

    final ArgumentCaptor<DailyTradeRecord> captor = ArgumentCaptor.forClass(DailyTradeRecord.class);
    verify(stateRepo).save(captor.capture());

    final DailyTradeRecord saved = captor.getValue();
    assertThat(saved.state()).isEqualTo(StrategyState.HEDGED);
    assertThat(saved.index()).isEqualTo(TargetIndex.NIFTY);

    assertThat(published).isNotEmpty();
    assertThat(published.get(0).title()).contains("[PAPER]").contains("09:45 Hedges Executed");
  }

  @Test
  void hedgeFailureAbortsAndBlocksTenAmStraddleEntry() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final NotificationPublisher publisher = mock(NotificationPublisher.class);
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T04:30:00Z"), IST); // 10:00 IST

    // No active hedges / repository empty
    when(stateRepo.load(any())).thenReturn(Optional.empty());

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeStraddle();

    // Zero sell orders placed! Safety interlock
    verify(gateway, never()).executeSellMarket(any(), any(), any(), anyInt());
  }

  @Test
  void vixAboveThresholdAbortsHedgeAndStraddleEntry() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T04:15:00Z"), IST); // 09:45 IST

    final InstrumentToken spotToken = new InstrumentToken(256265L);
    final InstrumentToken vixToken = new InstrumentToken(264969L);

    when(broker.quotes(any()))
        .thenAnswer(
            inv -> {
              final List<QuoteRef> refs = inv.getArgument(0);
              if (refs.stream().anyMatch(r -> r.token().equals(vixToken))) {
                return Map.of(
                    vixToken, makeQuote(vixToken, "INDIA VIX", BigDecimal.valueOf(26.50)));
              }
              return Map.of(
                  spotToken, makeQuote(spotToken, "NIFTY 50", BigDecimal.valueOf(25000.0)));
            });

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeHedge();

    // VIX 26.50 > maxVixThreshold 24.0 -> zero orders executed
    verify(gateway, never()).executeBuyMarket(any(), any(), any(), anyInt());
    verify(stateRepo, never()).save(any());

    assertThat(published).anyMatch(n -> n.title().contains("VIX Circuit Breaker Engaged"));
  }

  @Test
  void fifteenHundredSquareOffExitsAllActivePositionsAndMarksCompletedWithPnl() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T09:30:00Z"), IST); // 15:00 IST

    final TradeLeg hCe =
        new TradeLeg(
            "NIFTY_H_CE",
            new InstrumentToken(1L),
            130,
            BigDecimal.valueOf(3.50),
            BigDecimal.ZERO,
            true);
    final TradeLeg hPe =
        new TradeLeg(
            "NIFTY_H_PE",
            new InstrumentToken(2L),
            130,
            BigDecimal.valueOf(4.20),
            BigDecimal.ZERO,
            true);
    final TradeLeg sCe =
        new TradeLeg(
            "NIFTY_S_CE",
            new InstrumentToken(3L),
            130,
            BigDecimal.valueOf(120.0),
            BigDecimal.valueOf(156.0),
            true);
    final TradeLeg sPe =
        new TradeLeg(
            "NIFTY_S_PE",
            new InstrumentToken(4L),
            130,
            BigDecimal.valueOf(110.0),
            BigDecimal.valueOf(143.0),
            true);

    final DailyTradeRecord activeRecord =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            hCe,
            hPe,
            sCe,
            sPe,
            BigDecimal.ZERO);

    when(stateRepo.load(any())).thenReturn(Optional.of(activeRecord));

    when(gateway.executeBuyMarket(eq("NIFTY_S_CE"), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(15.0)); // Short CE closes at 15
    when(gateway.executeBuyMarket(eq("NIFTY_S_PE"), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(10.0)); // Short PE closes at 10
    when(gateway.executeSellMarket(eq("NIFTY_H_CE"), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(0.05)); // Hedge CE exits at 0.05
    when(gateway.executeSellMarket(eq("NIFTY_H_PE"), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(0.05)); // Hedge PE exits at 0.05

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeSquareOff();

    // 2 short legs bought to close, 2 long hedges sold to close
    verify(gateway, times(2)).executeBuyMarket(any(), any(), any(), eq(130));
    verify(gateway, times(2)).executeSellMarket(any(), any(), any(), eq(130));

    final ArgumentCaptor<DailyTradeRecord> captor = ArgumentCaptor.forClass(DailyTradeRecord.class);
    verify(stateRepo).save(captor.capture());
    final DailyTradeRecord completed = captor.getValue();

    assertThat(completed.state()).isEqualTo(StrategyState.COMPLETED);
    assertThat(completed.realizedPnl()).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  void tenAmStraddleSellsAtmAndPlacesExchangeLevelStopLossOrders() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T04:30:00Z"), IST); // 10:00 IST

    final TradeLeg hCe =
        new TradeLeg(
            "NIFTY_H_CE",
            new InstrumentToken(1L),
            130,
            BigDecimal.valueOf(3.50),
            BigDecimal.ZERO,
            true);
    final TradeLeg hPe =
        new TradeLeg(
            "NIFTY_H_PE",
            new InstrumentToken(2L),
            130,
            BigDecimal.valueOf(4.20),
            BigDecimal.ZERO,
            true);
    final DailyTradeRecord hedgedRecord =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.HEDGED,
            hCe,
            hPe,
            null,
            null,
            BigDecimal.ZERO);

    when(stateRepo.load(any())).thenReturn(Optional.of(hedgedRecord));

    final InstrumentToken spotToken = new InstrumentToken(256265L);
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(spotToken, makeQuote(spotToken, "NIFTY 50", BigDecimal.valueOf(25000.0))));

    when(gateway.executeSellMarket(any(), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(100.0));
    when(gateway.placeStopLossBuy(any(), any(), any(), anyInt(), any(), any()))
        .thenReturn(new OrderId("sl-101"), new OrderId("sl-102"));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeStraddle();

    // 2 sell market orders executed
    verify(gateway, times(2)).executeSellMarket(any(), any(), eq(Exchange.NFO), eq(130));

    // 2 exchange-level SL orders placed
    verify(gateway, times(2))
        .placeStopLossBuy(any(), any(), eq(Exchange.NFO), eq(130), any(), any());

    final ArgumentCaptor<DailyTradeRecord> captor = ArgumentCaptor.forClass(DailyTradeRecord.class);
    verify(stateRepo).save(captor.capture());
    final DailyTradeRecord saved = captor.getValue();

    assertThat(saved.state()).isEqualTo(StrategyState.STRADDLE_ACTIVE);
    assertThat(saved.shortCe().slOrderId()).isEqualTo(new OrderId("sl-101"));
    assertThat(saved.shortPe().slOrderId()).isEqualTo(new OrderId("sl-102"));
  }

  @Test
  void straddleAtomicRollbackUnwindsCallIfPutFails() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T04:30:00Z"), IST);

    final TradeLeg hCe =
        new TradeLeg(
            "NIFTY_H_CE",
            new InstrumentToken(1L),
            130,
            BigDecimal.valueOf(3.50),
            BigDecimal.ZERO,
            true);
    final TradeLeg hPe =
        new TradeLeg(
            "NIFTY_H_PE",
            new InstrumentToken(2L),
            130,
            BigDecimal.valueOf(4.20),
            BigDecimal.ZERO,
            true);
    final DailyTradeRecord hedgedRecord =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.HEDGED,
            hCe,
            hPe,
            null,
            null,
            BigDecimal.ZERO);

    when(stateRepo.load(any())).thenReturn(Optional.of(hedgedRecord));

    final InstrumentToken spotToken = new InstrumentToken(256265L);
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(spotToken, makeQuote(spotToken, "NIFTY 50", BigDecimal.valueOf(25000.0))));

    // CE sell succeeds, PE sell throws exception
    when(gateway.executeSellMarket(any(), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(100.0))
        .thenThrow(new RuntimeException("Price freeze on PE"));
    when(gateway.placeStopLossBuy(any(), any(), any(), anyInt(), any(), any()))
        .thenReturn(new OrderId("sl-ce-101"));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.executeStraddle();

    // Rollback: CE stop loss order cancelled
    verify(gateway).cancelOrder(eq(new OrderId("sl-ce-101")));

    // Rollback: CE bought back at market
    verify(gateway).executeBuyMarket(any(), any(), eq(Exchange.NFO), eq(130));

    // ABORTED state saved
    final ArgumentCaptor<DailyTradeRecord> captor = ArgumentCaptor.forClass(DailyTradeRecord.class);
    verify(stateRepo).save(captor.capture());
    assertThat(captor.getValue().state()).isEqualTo(StrategyState.ABORTED);

    // Critical alert published
    assertThat(published)
        .anyMatch(n -> n.title().contains("Rollback") || n.body().contains("Unwound CE"));
  }

  @Test
  void checkRiskHasScheduledAnnotationWithOneThousandFixedDelay() throws Exception {
    final java.lang.reflect.Method method = IntradayOptionStrategy.class.getMethod("checkRisk");
    assertThat(method.isAnnotationPresent(Scheduled.class)).isTrue();
    final Scheduled scheduled = method.getAnnotation(Scheduled.class);
    assertThat(scheduled.fixedDelay()).isEqualTo(1000);
  }

  @Test
  void checkRiskEvaluatesWhenStraddleIsActive() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final NotificationPublisher publisher = mock(NotificationPublisher.class);
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T05:00:00Z"), IST); // 10:30 IST

    final DailyTradeRecord active =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO);
    when(stateRepo.load(any())).thenReturn(Optional.of(active));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.checkRisk();

    verify(riskManager).evaluateRisk(eq(active));
  }

  @Test
  void checkRiskEvaluatesWhenOneLegStopped() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final NotificationPublisher publisher = mock(NotificationPublisher.class);
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), IST); // 11:30 IST

    final DailyTradeRecord oneLegStopped =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.ONE_LEG_STOPPED,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO);
    when(stateRepo.load(any())).thenReturn(Optional.of(oneLegStopped));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.checkRisk();

    verify(riskManager).evaluateRisk(eq(oneLegStopped));
  }

  @Test
  void checkRiskSkipsWhenIdleOrCompleted() {
    final Broker broker = mock(Broker.class);
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager = mock(PositionRiskManager.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final NotificationPublisher publisher = mock(NotificationPublisher.class);
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T09:40:00Z"), IST);

    final DailyTradeRecord completed =
        new DailyTradeRecord(
            LocalDate.now(clock),
            TargetIndex.NIFTY,
            StrategyState.COMPLETED,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO);
    when(stateRepo.load(any())).thenReturn(Optional.of(completed));

    final IntradayOptionStrategy strategy =
        new IntradayOptionStrategy(
            broker,
            gateway,
            resolver,
            riskManager,
            stateRepo,
            alertService,
            publisher,
            props,
            clock);

    strategy.checkRisk();

    verify(riskManager, never()).evaluateRisk(any());
  }
}
