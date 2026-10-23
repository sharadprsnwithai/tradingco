package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidInstantiatingObjectsInLoops"})
class ExecutionTrackerTest {

  private final Clock clock =
      Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneId.of("Asia/Kolkata"));

  private OrderRequest sampleRequest(String tag, int quantity) {
    return new OrderRequest(
        new InstrumentToken(256265L),
        "RELIANCE",
        Exchange.NSE,
        TransactionType.BUY,
        OrderType.LIMIT,
        Product.MIS,
        Validity.DAY,
        quantity,
        Optional.of(BigDecimal.valueOf(2500)),
        Optional.empty(),
        Optional.of(tag));
  }

  @Test
  void lifecycleTransitionsEmitEventsExactlyOnce() {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    final OrderId id = new OrderId("1001");
    tracker.register(id, "tag-1", sampleRequest("tag-1", 100));

    // OPEN does not emit event (adapter already emits SUBMITTED)
    final boolean openObserved =
        tracker.observe(new OrderSnapshot(id, OrderStatus.OPEN, 100, 0, "", clock.instant()));
    assertThat(openObserved).isTrue();
    assertThat(events).isEmpty();

    // PARTIAL emits ORDER_PARTIAL
    final boolean partialObserved =
        tracker.observe(new OrderSnapshot(id, OrderStatus.OPEN, 100, 40, "", clock.instant()));
    assertThat(partialObserved).isTrue();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).type()).isEqualTo(NotificationType.ORDER_PARTIAL);

    // FILLED emits ORDER_FILLED
    final boolean filledObserved =
        tracker.observe(new OrderSnapshot(id, OrderStatus.FILLED, 100, 100, "", clock.instant()));
    assertThat(filledObserved).isTrue();
    assertThat(events).hasSize(2);
    assertThat(events.get(1).type()).isEqualTo(NotificationType.ORDER_FILLED);
  }

  @Test
  void concurrentDuplicateFilledSnapshotsEmitExactlyOneFilledEvent() throws InterruptedException {
    final AtomicInteger filledEvents = new AtomicInteger(0);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final NotificationPublisher publisher =
        n -> {
          if (n.type() == NotificationType.ORDER_FILLED) {
            filledEvents.incrementAndGet();
          }
        };

    final ExecutionTracker tracker = new ExecutionTracker(publisher, registry, clock);
    final OrderId id = new OrderId("2002");
    tracker.register(id, "tag-2", sampleRequest("tag-2", 10));

    final int iterations = 50;
    final CountDownLatch latch = new CountDownLatch(iterations * 2);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < iterations * 2; i++) {
        pool.submit(
            () -> {
              try {
                tracker.observe(
                    new OrderSnapshot(id, OrderStatus.FILLED, 10, 10, "", clock.instant()));
              } finally {
                latch.countDown();
              }
            });
      }
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(filledEvents.get()).isEqualTo(1);
  }

  @Test
  void illegalTransitionFromFilledToOpenIsIgnored() {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    final OrderId id = new OrderId("3003");
    tracker.register(id, "tag-3", sampleRequest("tag-3", 10));
    tracker.observe(new OrderSnapshot(id, OrderStatus.FILLED, 10, 10, "", clock.instant()));
    assertThat(events).hasSize(1);

    // Illegal attempt to move backwards to OPEN
    final boolean accepted =
        tracker.observe(new OrderSnapshot(id, OrderStatus.OPEN, 10, 0, "", clock.instant()));
    assertThat(accepted).isFalse();
    assertThat(events).hasSize(1); // No new event
    assertThat(tracker.byTag("tag-3").orElseThrow().state()).isEqualTo(OrderState.FILLED);
  }

  @Test
  void rejectedOrderCarriesReasonStringInNotification() {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    final OrderId id = new OrderId("4004");
    tracker.register(id, "tag-4", sampleRequest("tag-4", 10));

    tracker.observe(
        new OrderSnapshot(id, OrderStatus.REJECTED, 10, 0, "Insufficient funds", clock.instant()));

    assertThat(events).hasSize(1);
    final Notification n = events.get(0);
    assertThat(n.type()).isEqualTo(NotificationType.ORDER_REJECTED);
    assertThat(n.body()).contains("Insufficient funds");
  }

  @Test
  void declareLostPublishesCriticalOrderLostAndIncrementsMetric() {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    final OrderId id = new OrderId("5005");
    tracker.register(id, "tag-5", sampleRequest("tag-5", 10));

    tracker.declareLost(id);

    assertThat(events).hasSize(1);
    final Notification n = events.get(0);
    assertThat(n.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(n.type()).isEqualTo(NotificationType.ORDER_LOST);
    assertThat(n.body()).contains("tag-5");

    final double lostCount = registry.get("tracker.orders.lost").counter().count();
    assertThat(lostCount).isEqualTo(1.0);
  }

  @Test
  void resolveAmbiguityMovesToSubmittedAndEmitsOrderSubmitted() {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    tracker.resolveAmbiguity("corr-tag-6", new OrderId("6006"));

    final Optional<TrackedOrder> order = tracker.byTag("corr-tag-6");
    assertThat(order).isPresent();
    assertThat(order.get().state()).isEqualTo(OrderState.SUBMITTED);
    assertThat(order.get().id()).isEqualTo(new OrderId("6006"));
    assertThat(events).hasSize(1);
    assertThat(events.get(0).type()).isEqualTo(NotificationType.ORDER_SUBMITTED);
  }

  @Test
  void burstFromEightThreadsMaintainsConsistentStateAndExactEventCount()
      throws InterruptedException {
    final List<Notification> events = new CopyOnWriteArrayList<>();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ExecutionTracker tracker = new ExecutionTracker(events::add, registry, clock);

    final int threadCount = 8;
    final int ordersPerThread = 25; // 200 total orders
    final CountDownLatch latch = new CountDownLatch(threadCount * ordersPerThread);

    try (ExecutorService pool = Executors.newFixedThreadPool(threadCount)) {
      for (int t = 0; t < threadCount; t++) {
        final int threadIdx = t;
        pool.submit(
            () -> {
              for (int i = 0; i < ordersPerThread; i++) {
                final String idStr = "burst-" + threadIdx + "-" + i;
                final OrderId id = new OrderId(idStr);
                final String tag = "tag-" + idStr;

                tracker.register(id, tag, sampleRequest(tag, 10));
                tracker.observe(
                    new OrderSnapshot(id, OrderStatus.OPEN, 10, 0, "", clock.instant()));
                tracker.observe(
                    new OrderSnapshot(id, OrderStatus.OPEN, 10, 5, "", clock.instant())); // partial
                tracker.observe(
                    new OrderSnapshot(
                        id, OrderStatus.FILLED, 10, 10, "", clock.instant())); // filled
                latch.countDown();
              }
            });
      }
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    // 200 orders × (1 PARTIAL + 1 FILLED) = 400 events
    assertThat(events).hasSize(400);

    final long partialCount =
        events.stream().filter(e -> e.type() == NotificationType.ORDER_PARTIAL).count();
    final long filledCount =
        events.stream().filter(e -> e.type() == NotificationType.ORDER_FILLED).count();

    assertThat(partialCount).isEqualTo(200);
    assertThat(filledCount).isEqualTo(200);
  }
}
