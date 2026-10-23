package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.health.RuntimeSignals;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically polls Kite order book during market hours and diffs state changes. */
@Component
public final class OrderPoller {

  private static final Logger LOG = LoggerFactory.getLogger(OrderPoller.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final Broker broker;
  private final ExecutionTracker tracker;
  private final RuntimeSignals signals;
  private final Counter pollerErrorCounter;
  private final Clock clock;

  @Autowired
  public OrderPoller(
      Broker broker, ExecutionTracker tracker, RuntimeSignals signals, MeterRegistry registry) {
    this(broker, tracker, signals, registry, Clock.system(IST));
  }

  public OrderPoller(
      Broker broker,
      ExecutionTracker tracker,
      RuntimeSignals signals,
      MeterRegistry registry,
      Clock clock) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
    this.signals = Objects.requireNonNull(signals, "signals must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(registry, "registry must not be null");

    this.pollerErrorCounter = registry.counter("kite.poller.errors");
  }

  /** Executes scheduled polling cycle every 5 seconds. */
  @Scheduled(fixedDelayString = "5000")
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public void poll() {
    if (!MarketHours.isOpen(clock.instant(), IST)) {
      return;
    }

    try {
      final List<Order> book = broker.orderBook();
      for (final Order order : book) {
        if (order.tag().isPresent() && !order.tag().get().isBlank()) {
          final OrderSnapshot snapshot =
              new OrderSnapshot(
                  order.orderId(),
                  order.status(),
                  order.quantity(),
                  order.filledQuantity(),
                  order.statusMessage(),
                  clock.instant());
          tracker.observe(snapshot);
        }
      }
      signals.markPollerRun(clock.instant().getEpochSecond());
    } catch (com.trading.bot.broker.api.error.BrokerException e) {
      pollerErrorCounter.increment();
      if (LOG.isErrorEnabled()) {
        LOG.error("Order book poll failed: {}", e.getMessage(), e);
      }
    }
  }
}
