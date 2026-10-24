package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory state machine for order execution tracking and exactly-once event emission.
 */
@Component
public final class ExecutionTracker {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionTracker.class);

  private final NotificationPublisher publisher;
  private final Counter lostOrdersCounter;
  private final Timer latencyTimer;
  private final Clock clock;

  private final Map<OrderId, TrackedOrderInternal> orders = new ConcurrentHashMap<>();
  private final Map<String, OrderId> tagToId = new ConcurrentHashMap<>();
  private final Set<String> ambiguousTags = ConcurrentHashMap.newKeySet();

  public ExecutionTracker(NotificationPublisher publisher, MeterRegistry registry, Clock clock) {
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    Objects.requireNonNull(registry, "registry must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");

    this.lostOrdersCounter = registry.counter("tracker.orders.lost");
    this.latencyTimer = registry.timer("kite.order.latency");
  }

  /**
   * Registers a newly submitted order to begin tracking.
   *
   * @param id order ID
   * @param tag correlation tag
   * @param request original order placement request
   */
  public void register(OrderId id, String tag, OrderRequest request) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(request, "request must not be null");

    final String safeTag = tag == null ? "" : tag;
    final TrackedOrderInternal internal =
        new TrackedOrderInternal(
            id,
            safeTag,
            OrderState.SUBMITTED,
            request.quantity(),
            0,
            "",
            clock.instant(),
            request.tradingsymbol(),
            request.transactionType().name(),
            Timer.start());

    orders.put(id, internal);
    if (!safeTag.isBlank()) {
      tagToId.put(safeTag, id);
    }
  }

  /**
   * Observes order status update from order book, driving exactly-once transitions.
   *
   * @param snapshot observed snapshot
   * @return true if state changed or new fill progress was observed
   */
  public boolean observe(OrderSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot must not be null");

    final TrackedOrderInternal order = orders.get(snapshot.id());
    if (order == null) {
      return false;
    }

    synchronized (order) {
      order.stopLatencyTimerIfRunning(latencyTimer);

      final OrderState targetState = mapTargetState(snapshot);
      if (targetState == null) {
        return false;
      }

      if (order.state.isTerminal() && !targetState.isTerminal()) {
        if (LOG.isWarnEnabled()) {
          LOG.warn(
              "Ignoring illegal transition from {} to {} for order {}",
              order.state,
              targetState,
              order.id.value());
        }
        return false;
      }

      final boolean isPartialProgress =
          targetState == OrderState.PARTIAL
              && order.state == OrderState.PARTIAL
              && snapshot.filled() != order.filled;

      if (order.state == targetState && !isPartialProgress) {
        return false;
      }

      order.state = targetState;
      order.filled = snapshot.filled();
      order.reason = snapshot.reason();

      emitEvent(order);
      return true;
    }
  }

  /**
   * Sole authority to declare an order LOST (called by Reconciler).
   *
   * @param id order ID
   */
  public void declareLost(OrderId id) {
    Objects.requireNonNull(id, "id must not be null");
    final TrackedOrderInternal order = orders.get(id);
    if (order == null) {
      return;
    }

    synchronized (order) {
      if (order.state == OrderState.LOST) {
        return;
      }
      order.state = OrderState.LOST;
      lostOrdersCounter.increment();

      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.ORDER_LOST,
              "Order declared LOST: " + id.value(),
              "Order "
                  + id.value()
                  + " [tag="
                  + order.tag
                  + "] declared LOST after grace elapsed"));
    }
  }

  /**
   * Registers a tag whose placement outcome is unknown (mutation failed ambiguously), pending
   * reconciliation against the order book.
   *
   * @param tag correlation tag of the ambiguous placement
   */
  public void registerAmbiguous(String tag) {
    if (tag != null && !tag.isBlank()) {
      ambiguousTags.add(tag);
    }
  }

  /**
   * Returns the tags whose placement outcome is still unknown.
   *
   * @return immutable snapshot of ambiguous tags
   */
  public Set<String> ambiguousOrders() {
    return Set.copyOf(ambiguousTags);
  }

  /**
   * Resolves ambiguity when an ambiguous order is found in the order book.
   *
   * @param correlationId correlation tag
   * @param found found order ID
   */
  public void resolveAmbiguity(String correlationId, OrderId found) {
    Objects.requireNonNull(correlationId, "correlationId must not be null");
    Objects.requireNonNull(found, "found must not be null");

    ambiguousTags.remove(correlationId);
    tagToId.put(correlationId, found);

    final TrackedOrderInternal order =
        orders.computeIfAbsent(
            found,
            id ->
                new TrackedOrderInternal(
                    id,
                    correlationId,
                    OrderState.SUBMITTED,
                    0,
                    0,
                    "",
                    clock.instant(),
                    "",
                    "",
                    null));

    synchronized (order) {
      order.state = OrderState.SUBMITTED;
      publisher.publish(
          Notification.of(
              Severity.INFO,
              NotificationType.ORDER_SUBMITTED,
              "Order resolved: " + found.value(),
              "Ambiguous order resolved with tag=" + correlationId));
    }
  }

  /**
   * Finds tracked order by tag.
   *
   * @param tag correlation tag
   * @return optional tracked order
   */
  public Optional<TrackedOrder> byTag(String tag) {
    if (tag == null || tag.isBlank()) {
      return Optional.empty();
    }
    final OrderId id = tagToId.get(tag);
    if (id == null) {
      return Optional.empty();
    }
    final TrackedOrderInternal internal = orders.get(id);
    return internal == null ? Optional.empty() : Optional.of(internal.toTrackedOrder());
  }

  /**
   * Returns list of currently open non-terminal orders.
   *
   * @return open orders
   */
  public List<TrackedOrder> openOrders() {
    return orders.values().stream()
        .filter(o -> !o.state.isTerminal())
        .map(TrackedOrderInternal::toTrackedOrder)
        .toList();
  }

  private OrderState mapTargetState(OrderSnapshot snapshot) {
    final OrderStatus s = snapshot.status();
    if (s == OrderStatus.FILLED) {
      return OrderState.FILLED;
    }
    if (s == OrderStatus.CANCELLED) {
      return OrderState.CANCELLED;
    }
    if (s == OrderStatus.REJECTED) {
      return OrderState.REJECTED;
    }
    if (s == OrderStatus.EXPIRED) {
      return OrderState.EXPIRED;
    }
    if (snapshot.filled() > 0
        && snapshot.filled() < snapshot.quantity()
        && !OrderStatus.isTerminal(s)) {
      return OrderState.PARTIAL;
    }
    if (s == OrderStatus.OPEN || s == OrderStatus.PENDING) {
      return OrderState.OPEN;
    }
    return null;
  }

  private void emitEvent(TrackedOrderInternal order) {
    final String details =
        order.tradingsymbol
            + " "
            + order.transactionType
            + " qty="
            + order.quantity
            + " filled="
            + order.filled
            + " tag="
            + order.tag;

    switch (order.state) {
      case FILLED ->
          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.ORDER_FILLED,
                  "Order filled: " + order.id.value(),
                  details));
      case PARTIAL ->
          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.ORDER_PARTIAL,
                  "Order partially filled: " + order.id.value(),
                  details));
      case REJECTED ->
          publisher.publish(
              Notification.of(
                  Severity.CRITICAL,
                  NotificationType.ORDER_REJECTED,
                  "Order rejected: " + order.id.value(),
                  details + " reason=" + order.reason));
      case CANCELLED, EXPIRED ->
          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.ORDER_CANCELLED,
                  "Order cancelled: " + order.id.value(),
                  details + " reason=" + order.reason));
      default -> {
        // OPEN and SUBMITTED emit no notification from tracker
      }
    }
  }

  private static final class TrackedOrderInternal {
    private final OrderId id;
    private final String tag;
    private OrderState state;
    private final int quantity;
    private int filled;
    private String reason;
    private final Instant submittedAt;
    private final String tradingsymbol;
    private final String transactionType;
    private Timer.Sample latencySample;

    TrackedOrderInternal(
        OrderId id,
        String tag,
        OrderState state,
        int quantity,
        int filled,
        String reason,
        Instant submittedAt,
        String tradingsymbol,
        String transactionType,
        Timer.Sample latencySample) {
      this.id = id;
      this.tag = tag;
      this.state = state;
      this.quantity = quantity;
      this.filled = filled;
      this.reason = reason;
      this.submittedAt = submittedAt;
      this.tradingsymbol = tradingsymbol;
      this.transactionType = transactionType;
      this.latencySample = latencySample;
    }

    @SuppressWarnings("PMD.NullAssignment")
    void stopLatencyTimerIfRunning(Timer timer) {
      if (latencySample != null) {
        latencySample.stop(timer);
        latencySample = null;
      }
    }

    TrackedOrder toTrackedOrder() {
      return new TrackedOrder(id, tag, state, quantity, filled, reason, submittedAt);
    }
  }
}
