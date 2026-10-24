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
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class PositionRiskManagerTest {

  private static final String CE_SYMBOL = "NIFTY26OCT25000CE";
  private static final String PE_SYMBOL = "NIFTY26OCT25000PE";

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
  void callLegBreachesThirtyPercentSlTriggersExitAndTrailsPutToBreakeven() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL, ceToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL, peToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // CE rises to 132.0 (>= 130.0 SL), PE drops to 70.0
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, CE_SYMBOL, BigDecimal.valueOf(132.0)),
                peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(70.0))));

    when(gateway.executeBuyMarket(eq(CE_SYMBOL), eq(ceToken), any(), eq(130)))
        .thenReturn(BigDecimal.valueOf(132.0));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    // CE exited
    assertThat(updated.shortCe().isOpen()).isFalse();
    assertThat(updated.shortCe().exitPrice()).isEqualByComparingTo(BigDecimal.valueOf(132.0));
    verify(gateway).executeBuyMarket(eq(CE_SYMBOL), eq(ceToken), any(), eq(130));

    // PE remains open, but SL is moved to entry price (100.0)
    assertThat(updated.shortPe().isOpen()).isTrue();
    assertThat(updated.shortPe().slPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.0));

    verify(stateRepo).save(updated);

    // Alert verified
    assertThat(published).isNotEmpty();
    assertThat(published.get(0).title()).contains("[PAPER]").contains("Stop-Loss Hit");
  }

  @Test
  void survivingLegWhipsawsBackToEntryPriceExitsAtBreakeven() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    // CE already closed, PE open with slPrice trailed to entry (100.0)
    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL,
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(132.0),
            BigDecimal.valueOf(130.0),
            false);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL,
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(100.0),
            true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.ONE_LEG_STOPPED,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // PE rises back to 100.5 (>= 100.0 breakeven SL)
    when(broker.quotes(any()))
        .thenReturn(Map.of(peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(100.5))));

    when(gateway.executeBuyMarket(eq(PE_SYMBOL), eq(peToken), any(), eq(130)))
        .thenReturn(BigDecimal.valueOf(100.5));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    assertThat(updated.shortPe().isOpen()).isFalse();
    verify(gateway).executeBuyMarket(eq(PE_SYMBOL), eq(peToken), any(), eq(130));

    assertThat(published).isNotEmpty();
    assertThat(published.get(0).title()).contains("[PAPER]").contains("Breakeven Stop Hit");
  }

  @Test
  void premiumsUnderStopLossDoNotTriggerExits() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final NotificationPublisher publisher = n -> {};
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL, ceToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL, peToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // Both premiums decayed / under SL
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, CE_SYMBOL, BigDecimal.valueOf(115.0)),
                peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(95.0))));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    assertThat(updated.shortCe().isOpen()).isTrue();
    assertThat(updated.shortPe().isOpen()).isTrue();
    verify(gateway, never()).executeBuyMarket(any(), any(), any(), anyInt());
  }

  @Test
  void doubleLegStopCollisionBothExitedCleanly() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final NotificationPublisher publisher = n -> {};
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL, ceToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL, peToken, 130, BigDecimal.valueOf(100.0), BigDecimal.valueOf(130.0), true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // Volatile spike where both CE and PE spiked above 130
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, CE_SYMBOL, BigDecimal.valueOf(135.0)),
                peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(135.0))));

    when(gateway.executeBuyMarket(eq(CE_SYMBOL), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(135.0));
    when(gateway.executeBuyMarket(eq(PE_SYMBOL), any(), any(), anyInt()))
        .thenReturn(BigDecimal.valueOf(135.0));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    assertThat(updated.shortCe().isOpen()).isFalse();
    assertThat(updated.shortPe().isOpen()).isFalse();
    verify(gateway, times(2)).executeBuyMarket(any(), any(), any(), anyInt());
  }

  @Test
  void reconcilesExchangeSlOrderExecutionWithoutDuplicateMarketBuy() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final OrderId ceSlOrderId = new OrderId("sl-order-ce-99");
    final OrderId peSlOrderId = new OrderId("sl-order-pe-99");

    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL,
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(130.0),
            ceSlOrderId,
            true);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL,
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(130.0),
            peSlOrderId,
            true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // OrderBook shows CE SL order was FILLED directly by exchange at 130.50
    final Order filledCeSlOrder =
        new Order(
            ceSlOrderId,
            Optional.of("sl-ce"),
            Exchange.NFO,
            CE_SYMBOL,
            TransactionType.BUY,
            OrderType.SL,
            Product.MIS,
            Validity.DAY,
            OrderStatus.FILLED,
            "COMPLETE",
            130,
            130,
            0,
            BigDecimal.valueOf(136.50),
            BigDecimal.valueOf(130.0),
            BigDecimal.valueOf(130.50),
            "",
            Instant.now());

    when(broker.orderBook()).thenReturn(List.of(filledCeSlOrder));
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, CE_SYMBOL, BigDecimal.valueOf(131.0)),
                peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(70.0))));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    // CE marked closed with fill price from exchange
    assertThat(updated.shortCe().isOpen()).isFalse();
    assertThat(updated.shortCe().exitPrice()).isEqualByComparingTo(BigDecimal.valueOf(130.50));

    // Zero duplicate buy market orders executed!
    verify(gateway, never()).executeBuyMarket(any(), any(), any(), anyInt());

    // Surviving PE leg's exchange SL order was modified to entry cost (100.0)
    verify(gateway).modifyStopLoss(eq(peSlOrderId), eq(130), eq(BigDecimal.valueOf(100.0)), any());
    assertThat(updated.shortPe().slPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.0));
  }

  @Test
  void shortLegDecayingPastProfitTargetClosesLegAndBooksProfit() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, alertService, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final OrderId ceSlOrderId = new OrderId("sl-ce-decay");
    final OrderId peSlOrderId = new OrderId("sl-pe-decay");

    final TradeLeg shortCe =
        new TradeLeg(
            CE_SYMBOL,
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(130.0),
            ceSlOrderId,
            true);
    final TradeLeg shortPe =
        new TradeLeg(
            PE_SYMBOL,
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.ZERO,
            BigDecimal.valueOf(130.0),
            peSlOrderId,
            true);

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.now(),
            TargetIndex.NIFTY,
            StrategyState.STRADDLE_ACTIVE,
            null,
            null,
            shortCe,
            shortPe,
            BigDecimal.ZERO);

    // CE LTP decayed to 12.0 (decayed 88% > 85% target), PE still at 80.0
    when(broker.orderBook()).thenReturn(List.of());
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, CE_SYMBOL, BigDecimal.valueOf(12.0)),
                peToken, makeQuote(peToken, PE_SYMBOL, BigDecimal.valueOf(80.0))));

    when(gateway.executeBuyMarket(eq(CE_SYMBOL), eq(ceToken), eq(Exchange.NFO), eq(130)))
        .thenReturn(BigDecimal.valueOf(12.05));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    // Resting SL order cancelled for CE
    verify(gateway).cancelOrder(eq(ceSlOrderId));

    // Buy market executed to close CE
    verify(gateway).executeBuyMarket(eq(CE_SYMBOL), eq(ceToken), eq(Exchange.NFO), eq(130));

    // CE closed, PE remains open
    assertThat(updated.shortCe().isOpen()).isFalse();
    assertThat(updated.shortCe().exitPrice()).isEqualByComparingTo(BigDecimal.valueOf(12.05));
    assertThat(updated.shortPe().isOpen()).isTrue();
    assertThat(updated.state()).isEqualTo(StrategyState.ONE_LEG_STOPPED);

    // Realized PnL credited: (100.0 - 12.05) * 130 = 87.95 * 130 = 11433.50
    assertThat(updated.realizedPnl()).isGreaterThan(BigDecimal.ZERO);

    assertThat(published).anyMatch(n -> n.title().contains("Profit Target Hit"));
  }
}
