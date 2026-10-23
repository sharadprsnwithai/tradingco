package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.broker.health.RuntimeSignals;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class ReconcilerTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final KiteProperties props =
      new KiteProperties(
          "k", "u", "p", "t", null, null, null, new KiteProperties.Orders(Duration.ofSeconds(30)));

  private Order makeTaggedOrder(String id, String tag, OrderStatus status) {
    return new Order(
        new OrderId(id),
        Optional.of(tag),
        Exchange.NSE,
        "RELIANCE",
        TransactionType.BUY,
        OrderType.LIMIT,
        Product.MIS,
        Validity.DAY,
        status,
        status.name(),
        10,
        0,
        10,
        BigDecimal.valueOf(2500),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "",
        Instant.now());
  }

  @Test
  void doubleMissAndGraceElapsedDeclaresOrderLost() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final NotificationPublisher publisher = n -> {};
    final RuntimeSignals signals = new RuntimeSignals();

    final long[] currentEpochSec = new long[] {1_000_000L};
    final Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return IST;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.ofEpochSecond(currentEpochSec[0]);
          }
        };

    // Friday 10:00 IST -> market open
    currentEpochSec[0] = Instant.parse("2026-10-23T04:30:00Z").getEpochSecond();

    final TrackedOrder tracked =
        new TrackedOrder(
            new OrderId("7007"), "tag-7007", OrderState.OPEN, 10, 0, "", clock.instant());
    when(tracker.openOrders()).thenReturn(List.of(tracked));

    // Order book is empty (order missing)
    when(broker.orderBook()).thenReturn(List.of());

    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    // Run 1: first miss -> no LOST
    reconciler.reconcile();
    verify(tracker, never()).declareLost(any());

    // Advance 10s: 2nd miss but grace (30s) not elapsed -> no LOST
    currentEpochSec[0] += 10L;
    reconciler.reconcile();
    verify(tracker, never()).declareLost(any());

    // Advance another 25s (total 35s > 30s lost grace) -> 3rd miss -> declareLost
    currentEpochSec[0] += 25L;
    reconciler.reconcile();
    verify(tracker, times(1)).declareLost(new OrderId("7007"));
  }

  @Test
  void startupRebuildRegistersUntrackedOrdersSilentlyAndPublishesGroupedRecovered() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);

    final Order o1 = makeTaggedOrder("1", "tag-1", OrderStatus.OPEN);
    final Order o2 = makeTaggedOrder("2", "tag-2", OrderStatus.OPEN);
    final Order o3AlreadyTracked = makeTaggedOrder("3", "tag-3", OrderStatus.OPEN);

    when(broker.orderBook()).thenReturn(List.of(o1, o2, o3AlreadyTracked));

    when(tracker.byTag("tag-1")).thenReturn(Optional.empty());
    when(tracker.byTag("tag-2")).thenReturn(Optional.empty());
    when(tracker.byTag("tag-3")).thenReturn(Optional.of(mock(TrackedOrder.class)));

    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    reconciler.rebuildOnStartup();

    // Must register the 2 untracked open orders
    verify(tracker, times(1)).register(eq(new OrderId("1")), eq("tag-1"), any());
    verify(tracker, times(1)).register(eq(new OrderId("2")), eq("tag-2"), any());
    verify(tracker, never()).register(eq(new OrderId("3")), eq("tag-3"), any());

    // Grouped notification: exactly 1 ORDER_RECOVERED listing both 1 and 2
    assertThat(published).hasSize(1);
    final Notification recovered = published.get(0);
    assertThat(recovered.type()).isEqualTo(NotificationType.ORDER_RECOVERED);
    assertThat(recovered.body()).contains("1").contains("2");
  }

  @Test
  void startupRebuildEmptyBookPublishesNoRecoveredEvent() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);

    when(broker.orderBook()).thenReturn(List.of());

    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    reconciler.rebuildOnStartup();

    assertThat(published).isEmpty();
  }
}
