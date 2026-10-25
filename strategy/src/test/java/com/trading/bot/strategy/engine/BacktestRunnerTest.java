package com.trading.bot.strategy.engine;

import static org.mockito.Mockito.mock;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.BacktestStrategyGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestRunnerTest {

  @TempDir Path tempDir;

  @Test
  void runThreeMonthBacktest() {
    final StrategyProperties props =
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

    final BacktestStrategyGateway gateway = new BacktestStrategyGateway();
    final StrategyStateRepository stateRepo = new StrategyStateRepository(tempDir.resolve("state.json"));
    final BacktestEngine engine = new BacktestEngine(props, gateway, stateRepo);

    final Map<LocalDate, Double> spotPrices = new HashMap<>();
    final List<LocalDate> tradingDays = new java.util.ArrayList<>();
    LocalDate start = LocalDate.now().minusMonths(3);
    for (LocalDate date = start; date.isBefore(LocalDate.now()); date = date.plusDays(1)) {
      if (date.getDayOfWeek().getValue() < 6) { // Mon-Fri
        spotPrices.put(date, 25000.0);
        tradingDays.add(date);
      }
    }

    final List<DailyTradeRecord> results = engine.runSimulation(tradingDays, spotPrices);
    
    BigDecimal totalPnl = results.stream()
        .map(DailyTradeRecord::realizedPnl)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
        
    System.out.println("==================================================");
    System.out.println(" 3-MONTH BACKTEST RESULTS (NIFTY & SENSEX) ");
    System.out.println("==================================================");
    System.out.println("Total Trading Days Simulated: " + tradingDays.size());
    System.out.println("Total Days Traded & Completed: " + results.size());
    System.out.println("Total Realized P&L: ₹" + totalPnl.toPlainString());
    System.out.println("==================================================");
  }
}
