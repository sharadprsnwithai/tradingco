package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.broker.health.RuntimeSignals;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled order reconciler with double-miss LOST rule and silent startup rebuild. */
@Component
public final class Reconciler {

  private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final Broker broker;
  private final ExecutionTracker tracker;
  private final NotificationPublisher publisher;
  private final KiteProperties props;
  private final RuntimeSignals signals;
  private final Clock clock;

  private final Map<OrderId, ObservationMeta> meta = new ConcurrentHashMap<>();

  @Autowired
  public Reconciler(
      Broker broker,
      ExecutionTracker tracker,
      NotificationPublisher publisher,
      KiteProperties props,
      RuntimeSignals signals) {
    this(broker, tracker, publisher, props, signals, Clock.system(IST));
  }

  public Reconciler(
      Broker broker,
      ExecutionTracker tracker,
      NotificationPublisher publisher,
      KiteProperties props,
      RuntimeSignals signals,
      Clock clock) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.signals = Objects.requireNonNull(signals, "signals must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Reconciles tracked open orders against Kite order book every 30 seconds. */
  @Scheduled(fixedDelayString = "30000")
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public void reconcile() {
    if (!MarketHours.isOpen(clock.instant(), IST)) {
      return;
    }

    final List<Order> book = broker.orderBook();
    final Map<OrderId, Order> bookMap =
        book.stream().collect(Collectors.toMap(Order::orderId, o -> o, (a, b) -> a));

    final List<TrackedOrder> openOrders = tracker.openOrders();
    for (final TrackedOrder tracked : openOrders) {
      final Order found = bookMap.get(tracked.id());
      if (found != null) {
        meta.put(tracked.id(), new ObservationMeta(0, clock.instant()));
        tracker.observe(
            new OrderSnapshot(
                found.orderId(),
                found.status(),
                found.quantity(),
                found.filledQuantity(),
                found.statusMessage(),
                clock.instant()));
      } else {
        final ObservationMeta m =
            meta.computeIfAbsent(tracked.id(), k -> new ObservationMeta(0, tracked.submittedAt()));
        m.consecutiveMisses++;

        final Duration timeSincePositive = Duration.between(m.lastPositive, clock.instant());
        if (m.consecutiveMisses >= 2
            && timeSincePositive.compareTo(props.orders().lostGrace()) >= 0) {
          tracker.declareLost(tracked.id());
          meta.remove(tracked.id());
        }
      }
    }

    signals.markReconcilerRun(clock.instant().getEpochSecond());
  }

  /** Performs silent rebuild of tracked open orders upon application startup. */
  @EventListener(ApplicationReadyEvent.class)
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public void rebuildOnStartup() {
    final List<Order> book;
    try {
      book = broker.orderBook();
    } catch (com.trading.bot.broker.api.error.BrokerException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed to fetch order book on startup rebuild: {}", e.getMessage(), e);
      }
      return;
    }
    final List<String> recoveredIds = new ArrayList<>();

    for (final Order order : book) {
      if (order.tag().isPresent() && !order.tag().get().isBlank()) {
        final String tag = order.tag().get();
        if (!OrderStatus.isTerminal(order.status()) && tracker.byTag(tag).isEmpty()) {
          final OrderRequest syntheticReq =
              new OrderRequest(
                  new InstrumentToken(0L),
                  order.tradingsymbol(),
                  order.exchange(),
                  order.transactionType(),
                  order.orderType(),
                  order.product(),
                  order.validity(),
                  order.quantity(),
                  Optional.ofNullable(order.price()),
                  Optional.ofNullable(order.triggerPrice()),
                  Optional.of(tag));

          tracker.register(order.orderId(), tag, syntheticReq);
          tracker.observe(
              new OrderSnapshot(
                  order.orderId(),
                  order.status(),
                  order.quantity(),
                  order.filledQuantity(),
                  order.statusMessage(),
                  clock.instant()));
          recoveredIds.add(order.orderId().value());
        }
        tracker.resolveAmbiguity(tag, order.orderId());
      }
    }

    if (!recoveredIds.isEmpty()) {
      publisher.publish(
          Notification.of(
              Severity.INFO,
              NotificationType.ORDER_RECOVERED,
              "Orders recovered: " + recoveredIds.size(),
              "Recovered open orders on startup: " + String.join(", ", recoveredIds)));
    }
  }

  private static final class ObservationMeta {
    private int consecutiveMisses;
    private final Instant lastPositive;

    ObservationMeta(int consecutiveMisses, Instant lastPositive) {
      this.consecutiveMisses = consecutiveMisses;
      this.lastPositive = lastPositive;
    }
  }
}
