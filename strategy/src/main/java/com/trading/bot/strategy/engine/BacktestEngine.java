package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.strategy.config.ExecutionMode;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.BacktestStrategyGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.StrategyState;
import com.trading.bot.strategy.model.TargetIndex;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Backtesting engine driving IntradayOptionStrategy over historical candle data. */
public final class BacktestEngine {

  private static final Logger LOG = LoggerFactory.getLogger(BacktestEngine.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final StrategyProperties props;
  private final Broker mockBroker;
  private final BacktestStrategyGateway gateway;
  private final StrategyStateRepository stateRepository;

  public BacktestEngine(
      StrategyProperties props,
      Broker mockBroker,
      BacktestStrategyGateway gateway,
      StrategyStateRepository stateRepository) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.mockBroker = Objects.requireNonNull(mockBroker, "mockBroker must not be null");
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository must not be null");
  }

  /**
   * Runs the backtest simulation over a series of trading days and historical spot/option candles.
   *
   * @param tradingDays list of dates to simulate
   * @param spotPrices map of date/index to spot price
   * @return list of completed DailyTradeRecords
   */
  public List<DailyTradeRecord> runSimulation(
      List<LocalDate> tradingDays, Map<LocalDate, Double> spotPrices) {
    Objects.requireNonNull(tradingDays, "tradingDays must not be null");
    Objects.requireNonNull(spotPrices, "spotPrices must not be null");

    final List<DailyTradeRecord> results = new java.util.ArrayList<>();
    final InstrumentResolver resolver = new InstrumentResolver();
    final PositionRiskManager riskManager =
        new PositionRiskManager(gateway, stateRepository, mockBroker, new StrategyAlertService(n -> {}), props);
    final NotificationPublisherDummy publisher = new NotificationPublisherDummy();

    for (final LocalDate date : tradingDays) {
      stateRepository.clear();
      final Double spot = spotPrices.get(date);
      if (spot == null || spot <= 0) {
        continue;
      }

      final Clock dayClock = Clock.fixed(date.atTime(9, 15).atZone(IST).toInstant(), IST);
      final IntradayOptionStrategy strategy =
          new IntradayOptionStrategy(
              mockBroker,
              gateway,
              resolver,
              riskManager,
              stateRepository,
              new StrategyAlertService(publisher),
              publisher,
              props,
              dayClock);

      final TargetIndex index = strategy.getTargetIndexForDate(date);
      if (index == null) {
        continue;
      }

      // 09:45 Hedge
      strategy.executeHedge();

      // 10:00 Straddle
      strategy.executeStraddle();

      // Simulate risk loop monitoring (e.g. 5 minutes intervals or minute intervals)
      for (int m = 0; m < 300; m += 5) {
        final LocalTime t = LocalTime.of(10, 0).plusMinutes(m);
        if (t.isAfter(props.exitTime())) {
          break;
        }
        // Simulate slight option price decay / fluctuation
        simulateOptionPricing(index, spot, gateway);
        strategy.checkRisk();
      }

      // 15:00 Square off
      strategy.executeSquareOff();

      stateRepository.load(date).ifPresent(results::add);
    }

    return results;
  }

  private void simulateOptionPricing(TargetIndex index, double spot, BacktestStrategyGateway gw) {
    // Simulated pricing helper for backtest
  }

  private static final class NotificationPublisherDummy
      implements com.trading.bot.notifications.api.NotificationPublisher {
    @Override
    public void publish(com.trading.bot.notifications.api.Notification notification) {}
  }
}
