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
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Quote;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
class PositionRiskManagerTest {

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

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, publisher, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            "NIFTY26OCT25000CE",
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
            true);
    final TradeLeg shortPe =
        new TradeLeg(
            "NIFTY26OCT25000PE",
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
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

    // CE rises to 132.0 (>= 130.0 SL), PE drops to 70.0
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, "NIFTY26OCT25000CE", BigDecimal.valueOf(132.0)),
                peToken, makeQuote(peToken, "NIFTY26OCT25000PE", BigDecimal.valueOf(70.0))));

    when(gateway.executeBuyMarket(eq("NIFTY26OCT25000CE"), eq(ceToken), any(), eq(130)))
        .thenReturn(BigDecimal.valueOf(132.0));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    // CE exited
    assertThat(updated.shortCe().isOpen()).isFalse();
    verify(gateway).executeBuyMarket(eq("NIFTY26OCT25000CE"), eq(ceToken), any(), eq(130));

    // PE remains open, but SL is moved to entry price (100.0)
    assertThat(updated.shortPe().isOpen()).isTrue();
    assertThat(updated.shortPe().slPrice()).isEqualByComparingTo(BigDecimal.valueOf(100.0));

    verify(stateRepo).save(updated);
  }

  @Test
  void survivingLegWhipsawsBackToEntryPriceExitsAtBreakeven() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, publisher, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    // CE already closed, PE open with slPrice trailed to entry (100.0)
    final TradeLeg shortCe =
        new TradeLeg(
            "NIFTY26OCT25000CE",
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
            false);
    final TradeLeg shortPe =
        new TradeLeg(
            "NIFTY26OCT25000PE",
            peToken,
            130,
            BigDecimal.valueOf(100.0),
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
        .thenReturn(
            Map.of(peToken, makeQuote(peToken, "NIFTY26OCT25000PE", BigDecimal.valueOf(100.5))));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    assertThat(updated.shortPe().isOpen()).isFalse();
    verify(gateway).executeBuyMarket(eq("NIFTY26OCT25000PE"), eq(peToken), any(), eq(130));
  }

  @Test
  void premiumsUnderStopLossDoNotTriggerExits() {
    final StrategyExecutionGateway gateway = mock(StrategyExecutionGateway.class);
    final StrategyStateRepository stateRepo = mock(StrategyStateRepository.class);
    final Broker broker = mock(Broker.class);
    final NotificationPublisher publisher = n -> {};

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, publisher, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            "NIFTY26OCT25000CE",
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
            true);
    final TradeLeg shortPe =
        new TradeLeg(
            "NIFTY26OCT25000PE",
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
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

    // Both premiums decayed / under SL
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, "NIFTY26OCT25000CE", BigDecimal.valueOf(115.0)),
                peToken, makeQuote(peToken, "NIFTY26OCT25000PE", BigDecimal.valueOf(95.0))));

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

    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepo, broker, publisher, props);

    final InstrumentToken ceToken = new InstrumentToken(201L);
    final InstrumentToken peToken = new InstrumentToken(202L);

    final TradeLeg shortCe =
        new TradeLeg(
            "NIFTY26OCT25000CE",
            ceToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
            true);
    final TradeLeg shortPe =
        new TradeLeg(
            "NIFTY26OCT25000PE",
            peToken,
            130,
            BigDecimal.valueOf(100.0),
            BigDecimal.valueOf(130.0),
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

    // Volatile spike where both CE and PE spiked above 130
    when(broker.quotes(any()))
        .thenReturn(
            Map.of(
                ceToken, makeQuote(ceToken, "NIFTY26OCT25000CE", BigDecimal.valueOf(135.0)),
                peToken, makeQuote(peToken, "NIFTY26OCT25000PE", BigDecimal.valueOf(135.0))));

    final DailyTradeRecord updated = riskManager.evaluateRisk(record);

    assertThat(updated.shortCe().isOpen()).isFalse();
    assertThat(updated.shortPe().isOpen()).isFalse();
    verify(gateway, times(2)).executeBuyMarket(any(), any(), any(), anyInt());
  }
}
