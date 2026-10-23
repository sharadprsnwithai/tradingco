package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.TargetIndex;
import com.trading.bot.strategy.model.TradeLeg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class StrategyAlertServiceTest {

  @Test
  void formatsCleanExecutionNotificationsWithoutInfoTag() {
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final StrategyAlertService alertService = new StrategyAlertService(publisher);

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

    final DailyTradeRecord record =
        new DailyTradeRecord(
            LocalDate.of(2026, 10, 26),
            TargetIndex.NIFTY,
            StrategyState.HEDGED,
            hCe,
            hPe,
            sCe,
            sPe,
            BigDecimal.ZERO);

    // 1. Hedge notification in PAPER mode
    alertService.notifyHedgeExecution(record, 25000.0, ExecutionMode.PAPER);
    assertThat(published).hasSize(1);
    final Notification hedge = published.get(0);
    assertThat(hedge.title()).startsWith("[PAPER]");
    assertThat(hedge.body()).contains("Spot: 25000.00").contains("Long CE").contains("Long PE");

    // 2. Straddle notification in LIVE mode
    alertService.notifyStraddleExecution(record, 25020.0, ExecutionMode.LIVE);
    assertThat(published).hasSize(2);
    final Notification straddle = published.get(1);
    assertThat(straddle.title()).startsWith("[LIVE]");
    assertThat(straddle.body()).contains("Short CE").contains("Short PE").contains("SL: ₹156.0");

    // 3. Stop-Loss notification
    alertService.notifyStopLossHit(sCe, BigDecimal.valueOf(156.0), sPe, ExecutionMode.PAPER);
    assertThat(published).hasSize(3);
    final Notification slAlert = published.get(2);
    assertThat(slAlert.title()).contains("Stop-Loss Hit");
    assertThat(slAlert.body()).contains("trailed to Breakeven");

    // 4. Final square-off notification with total realized P&L
    alertService.notifyFinalSquareOff(record, BigDecimal.valueOf(15500.25), ExecutionMode.PAPER);
    assertThat(published).hasSize(4);
    final Notification finalAlert = published.get(3);
    assertThat(finalAlert.title()).contains("Daily Square-Off & Final P&L");
    assertThat(finalAlert.body()).contains("Total Realized P&L: ₹15500.25");
  }
}
