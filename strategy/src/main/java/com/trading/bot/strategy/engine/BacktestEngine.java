package com.trading.bot.strategy.engine;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.HistoricalCandle;
import com.trading.bot.broker.api.model.Holding;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Margins;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderPatch;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.Position;
import com.trading.bot.broker.api.model.Quote;
import com.trading.bot.broker.api.model.QuoteRef;
import com.trading.bot.strategy.config.StrategyProperties;
import com.trading.bot.strategy.gateway.BacktestStrategyGateway;
import com.trading.bot.strategy.model.DailyTradeRecord;
import com.trading.bot.strategy.model.TargetIndex;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Backtesting engine driving IntradayOptionStrategy over historical candle data. */
public final class BacktestEngine {

  private static final Logger LOG = LoggerFactory.getLogger(BacktestEngine.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final StrategyProperties props;
  private final BacktestStrategyGateway gateway;
  private final StrategyStateRepository stateRepository;

  public BacktestEngine(
      StrategyProperties props,
      BacktestStrategyGateway gateway,
      StrategyStateRepository stateRepository) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository must not be null");
  }

  public List<DailyTradeRecord> runSimulation(
      List<LocalDate> tradingDays, Map<LocalDate, Double> spotPrices) {
    Objects.requireNonNull(tradingDays, "tradingDays must not be null");
    Objects.requireNonNull(spotPrices, "spotPrices must not be null");

    final List<DailyTradeRecord> results = new java.util.ArrayList<>();
    final InstrumentResolver resolver = new InstrumentResolver();

    for (final LocalDate date : tradingDays) {
      stateRepository.clear();
      final Double spot = spotPrices.get(date);
      if (spot == null || spot <= 0) {
        continue;
      }

      final BacktestBrokerStub brokerStub = new BacktestBrokerStub(date, spot, gateway);
      final PositionRiskManager riskManager =
          new PositionRiskManager(gateway, stateRepository, brokerStub, new StrategyAlertService(n -> {}), props);
      final NotificationPublisherDummy publisher = new NotificationPublisherDummy();

      final Clock dayClock = Clock.fixed(date.atTime(9, 15).atZone(IST).toInstant(), IST);
      final IntradayOptionStrategy strategy =
          new IntradayOptionStrategy(
              brokerStub,
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

      // Simulate risk loop monitoring
      for (int m = 0; m < 300; m += 5) {
        final LocalTime t = LocalTime.of(10, 0).plusMinutes(m);
        if (t.isAfter(props.exitTime())) {
          break;
        }
        strategy.checkRisk();
      }

      // 15:00 Square off
      strategy.executeSquareOff();

      stateRepository.load(date).ifPresent(results::add);
    }

    return results;
  }

  private static class BacktestBrokerStub implements Broker {
    private final LocalDate date;
    private final Double spot;
    private final BacktestStrategyGateway gateway;
    private int tickCount = 0;

    BacktestBrokerStub(LocalDate date, Double spot, BacktestStrategyGateway gateway) {
      this.date = date;
      this.spot = spot;
      this.gateway = gateway;
    }

    @Override
    public Map<InstrumentToken, Quote> quotes(Collection<QuoteRef> refs) {
      tickCount++;
      Map<InstrumentToken, Quote> map = new HashMap<>();
      for (QuoteRef ref : refs) {
        BigDecimal price;
        if (ref.token().value() == IntradayOptionStrategy.INDIA_VIX_TOKEN) {
          price = BigDecimal.valueOf(14.0);
        } else if (ref.token().value() == 256265L || ref.token().value() == 265L) { // Spot
          price = BigDecimal.valueOf(spot);
        } else {
          // Model realistic steady theta decay: options decay by ~50% over the day smoothly
          double initialPremium = 120.0;
          double decayProgress = (double) tickCount / 60.0; // 60 ticks max
          double p = Math.max(20.0, initialPremium * (1.0 - (decayProgress * 0.5)));
          price = BigDecimal.valueOf(p);
        }
        map.put(ref.token(), new Quote(ref.token(), ref.tradingsymbol(), price, price, price, price, price, 1000L));
        gateway.setCurrentPrice(ref.token(), price);
      }
      return map;
    }

    @Override public List<Order> orderBook() { return List.of(); }
    @Override public OrderId placeOrder(OrderRequest request) { return new OrderId("bk-ord-" + System.currentTimeMillis()); }
    @Override public void modifyOrder(OrderId id, OrderPatch patch) {}
    @Override public void cancelOrder(OrderId id) {}
    @Override public List<Position> positions() { return List.of(); }
    @Override public List<Holding> holdings() { return List.of(); }
    @Override public Margins margins() { return null; }
    @Override public List<HistoricalCandle> historicalCandles(InstrumentToken token, String interval, String from, String to) { return List.of(); }
  }

  private static final class NotificationPublisherDummy
      implements com.trading.bot.notifications.api.NotificationPublisher {
    @Override
    public void publish(com.trading.bot.notifications.api.Notification notification) {}
  }
}
