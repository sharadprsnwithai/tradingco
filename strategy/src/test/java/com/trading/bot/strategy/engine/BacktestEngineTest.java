package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.BacktestStrategyGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestEngineTest {

  @TempDir Path tempDir;

  @Test
  void runsSimulationAndProducesTradeRecords() throws IOException {
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

    final Broker mockBroker = mock(Broker.class);
    when(mockBroker.quotes(any()))
        .thenAnswer(
            inv -> {
              List<com.trading.bot.broker.api.model.QuoteRef> refs = inv.getArgument(0);
              Map<com.trading.bot.broker.api.model.InstrumentToken, Quote> map = new java.util.HashMap<>();
              for (var ref : refs) {
                BigDecimal price = BigDecimal.valueOf(25000.0);
                if (ref.token().value() == 264969L) { // INDIA VIX TOKEN
                  price = BigDecimal.valueOf(14.0);
                }
                map.put(
                    ref.token(),
                    new Quote(
                        ref.token(),
                        ref.tradingsymbol(),
                        price,
                        price,
                        price,
                        price,
                        price,
                        1000L));
              }
              return map;
            });

    final BacktestStrategyGateway gateway = new BacktestStrategyGateway();
    final StrategyStateRepository stateRepo =
        new StrategyStateRepository(tempDir.resolve("state.json"));

    final BacktestEngine engine = new BacktestEngine(props, mockBroker, gateway, stateRepo);

    final LocalDate monday = LocalDate.of(2026, 10, 26); // Monday -> Nifty
    final List<LocalDate> days = List.of(monday);
    final Map<LocalDate, Double> spots = Map.of(monday, 25000.0);

    final List<DailyTradeRecord> results = engine.runSimulation(days, spots);
    assertThat(results).isNotEmpty();
    assertThat(results.get(0).state()).isEqualTo(StrategyState.COMPLETED);
  }
}
