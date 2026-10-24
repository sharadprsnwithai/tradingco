package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.AmbiguousOrderStateException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.InstrumentToken;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderRequest;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.broker.health.RuntimeSignals;
import com.trading.bot.broker.kite.dto.PlaceOrderResponseDto;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.AvoidDuplicateLiterals"})
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

  @Test
  void startupRebuildWithRealTrackerEmitsZeroSubmittedAndNeverReadoptsTerminalOrders() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);
    final ExecutionTracker tracker =
        new ExecutionTracker(publisher, new SimpleMeterRegistry(), clock);

    final Order filled = makeTaggedOrder("1", "tag-1", OrderStatus.FILLED);
    final Order cancelled = makeTaggedOrder("2", "tag-2", OrderStatus.CANCELLED);
    final Order open = makeTaggedOrder("3", "tag-3", OrderStatus.OPEN);
    when(broker.orderBook()).thenReturn(List.of(filled, cancelled, open));

    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    reconciler.rebuildOnStartup();

    // T13(d): silent rebuild — zero ORDER_SUBMITTED regardless of book contents
    assertThat(published.stream().filter(n -> n.type() == NotificationType.ORDER_SUBMITTED))
        .isEmpty();

    // Terminal orders are not re-adopted into the tracker
    assertThat(tracker.byTag("tag-1")).isEmpty();
    assertThat(tracker.byTag("tag-2")).isEmpty();

    // Open untracked order is adopted silently with its book status
    assertThat(tracker.byTag("tag-3")).isPresent();
    assertThat(tracker.byTag("tag-3").orElseThrow().state()).isEqualTo(OrderState.OPEN);

    // Only the grouped ORDER_RECOVERED notification is published
    assertThat(published).hasSize(1);
    assertThat(published.get(0).type()).isEqualTo(NotificationType.ORDER_RECOVERED);
  }

  @Test
  void startupRebuildResolvesGenuinelyAmbiguousTagToSubmittedOnce() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);
    final ExecutionTracker tracker =
        new ExecutionTracker(publisher, new SimpleMeterRegistry(), clock);

    // Placement outcome was never confirmed before "restart"
    tracker.registerAmbiguous("tag-amb");
    when(broker.orderBook()).thenReturn(List.of(makeTaggedOrder("9", "tag-amb", OrderStatus.OPEN)));

    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    reconciler.rebuildOnStartup();

    final long submitted =
        published.stream().filter(n -> n.type() == NotificationType.ORDER_SUBMITTED).count();
    assertThat(submitted).isEqualTo(1L);
    assertThat(tracker.byTag("tag-amb")).isPresent();
    assertThat(tracker.byTag("tag-amb").orElseThrow().state()).isEqualTo(OrderState.SUBMITTED);
    assertThat(tracker.byTag("tag-amb").orElseThrow().id()).isEqualTo(new OrderId("9"));
    assertThat(tracker.ambiguousOrders()).isEmpty();
  }

  @Test
  void midSessionAmbiguityResolvesWhenTagFoundInBookThenBecomesNoop() {
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);
    final ExecutionTracker tracker =
        new ExecutionTracker(publisher, new SimpleMeterRegistry(), clock);

    // place -> wire timeout -> AmbiguousOrderStateException through the real adapter
    final KiteHttpClient httpClient = mock(KiteHttpClient.class);
    when(httpClient.post(
            eq("/orders/regular"), any(), eq(PlaceOrderResponseDto.class), eq(Mutability.MUTATION)))
        .thenThrow(
            new AmbiguousOrderStateException("/orders/regular", "tag-mid", "socket timeout"));
    final KiteBroker kiteBroker = new KiteBroker(httpClient, tracker, publisher, clock);

    final OrderRequest request =
        new OrderRequest(
            new InstrumentToken(0L),
            "RELIANCE",
            Exchange.NSE,
            TransactionType.BUY,
            OrderType.MARKET,
            Product.MIS,
            Validity.DAY,
            10,
            Optional.empty(),
            Optional.empty(),
            Optional.of("tag-mid"));

    assertThatThrownBy(() -> kiteBroker.placeOrder(request))
        .isInstanceOf(AmbiguousOrderStateException.class);
    assertThat(tracker.ambiguousOrders()).containsExactly("tag-mid");

    // Reconcile sees the tag in the book -> resolves to SUBMITTED, published exactly once
    final Broker broker = mock(Broker.class);
    when(broker.orderBook())
        .thenReturn(List.of(makeTaggedOrder("55", "tag-mid", OrderStatus.OPEN)));
    final Reconciler reconciler = new Reconciler(broker, tracker, publisher, props, signals, clock);

    reconciler.reconcile();

    final long submittedAfterFirst =
        published.stream().filter(n -> n.type() == NotificationType.ORDER_SUBMITTED).count();
    assertThat(submittedAfterFirst).isEqualTo(1L);
    assertThat(tracker.byTag("tag-mid")).isPresent();
    assertThat(tracker.byTag("tag-mid").orElseThrow().state()).isEqualTo(OrderState.SUBMITTED);
    assertThat(tracker.ambiguousOrders()).isEmpty();

    // Subsequent reconcile is a no-op for the resolved tag
    reconciler.reconcile();
    final long submittedAfterSecond =
        published.stream().filter(n -> n.type() == NotificationType.ORDER_SUBMITTED).count();
    assertThat(submittedAfterSecond).isEqualTo(1L);
  }
}
