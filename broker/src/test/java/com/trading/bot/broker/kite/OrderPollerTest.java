package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.NetworkException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.Order;
import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderStatus;
import com.trading.bot.broker.api.model.OrderType;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.broker.api.model.TransactionType;
import com.trading.bot.broker.api.model.Validity;
import com.trading.bot.broker.health.RuntimeSignals;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class OrderPollerTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
  void marketClosedPerformsZeroBrokerCalls() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final RuntimeSignals signals = new RuntimeSignals();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // Sunday 12:00 IST -> closed
    final Clock clock = Clock.fixed(Instant.parse("2026-10-25T06:30:00Z"), IST);

    final OrderPoller poller = new OrderPoller(broker, tracker, signals, registry, clock);
    poller.poll();

    verify(broker, never()).orderBook();
    assertThat(signals.lastPollerRun()).isEqualTo(0L);
  }

  @Test
  void marketOpenObservesSnapshotsAndMarksSignal() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final RuntimeSignals signals = new RuntimeSignals();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    // Friday 10:00 IST -> open
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);

    final Order order = makeTaggedOrder("101", "tag-101", OrderStatus.OPEN);
    when(broker.orderBook()).thenReturn(List.of(order));

    final OrderPoller poller = new OrderPoller(broker, tracker, signals, registry, clock);
    poller.poll();

    verify(broker).orderBook();
    verify(tracker).observe(any(OrderSnapshot.class));
    assertThat(signals.lastPollerRun()).isEqualTo(clock.instant().getEpochSecond());
  }

  @Test
  void brokerExceptionIncrementsCounterAndAllowsNextRun() {
    final Broker broker = mock(Broker.class);
    final ExecutionTracker tracker = mock(ExecutionTracker.class);
    final RuntimeSignals signals = new RuntimeSignals();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);

    when(broker.orderBook())
        .thenThrow(new NetworkException("Timeout connecting to Kite"))
        .thenReturn(List.of(makeTaggedOrder("102", "tag-102", OrderStatus.OPEN)));

    final OrderPoller poller = new OrderPoller(broker, tracker, signals, registry, clock);

    // Run 1: throws
    poller.poll();
    assertThat(registry.get("kite.poller.errors").counter().count()).isEqualTo(1.0);
    assertThat(signals.lastPollerRun()).isEqualTo(0L);

    // Run 2: succeeds
    poller.poll();
    verify(broker, times(2)).orderBook();
    assertThat(signals.lastPollerRun()).isEqualTo(clock.instant().getEpochSecond());
  }
}
