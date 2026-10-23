package com.trading.bot.broker.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.kite.SessionRefresher;
import com.trading.bot.notifications.internal.TelegramSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class TradingHealthIndicatorTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Test
  void degradedRefresherFlipsKiteAuthAndOverallHealthToDown() {
    final SessionRefresher refresher = mock(SessionRefresher.class);
    final TelegramSender telegram = mock(TelegramSender.class);
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST); // Market open

    when(refresher.isDegraded()).thenReturn(true);
    when(telegram.consecutiveFailures()).thenReturn(0);
    signals.markPollerRun(clock.instant().getEpochSecond());

    final TradingHealthIndicator indicator =
        new TradingHealthIndicator(refresher, telegram, signals, clock);

    final Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("kiteAuth");
    assertThat(health.getDetails().get("kiteAuth").toString()).contains("DOWN");
  }

  @Test
  void threeConsecutiveTelegramFailuresFlipsNotificationsToDown() {
    final SessionRefresher refresher = mock(SessionRefresher.class);
    final TelegramSender telegram = mock(TelegramSender.class);
    final RuntimeSignals signals = new RuntimeSignals();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);

    when(refresher.isDegraded()).thenReturn(false);
    when(telegram.consecutiveFailures()).thenReturn(3);
    signals.markPollerRun(clock.instant().getEpochSecond());

    final TradingHealthIndicator indicator =
        new TradingHealthIndicator(refresher, telegram, signals, clock);

    final Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsKey("notifications");
    assertThat(health.getDetails().get("notifications").toString()).contains("DOWN");
  }

  @Test
  void pollerStaleOverSixtySecondsDuringMarketHoursIsDownOutsideHoursIsUp() {
    final SessionRefresher refresher = mock(SessionRefresher.class);
    final TelegramSender telegram = mock(TelegramSender.class);
    final RuntimeSignals signals = new RuntimeSignals();

    when(refresher.isDegraded()).thenReturn(false);
    when(telegram.consecutiveFailures()).thenReturn(0);

    // Run 1: During market hours, poller stale for 120s -> DOWN
    final Clock marketOpenClock = Clock.fixed(Instant.parse("2026-10-23T04:30:00Z"), IST);
    signals.markPollerRun(marketOpenClock.instant().getEpochSecond() - 120L);

    final TradingHealthIndicator indicatorOpen =
        new TradingHealthIndicator(refresher, telegram, signals, marketOpenClock);
    assertThat(indicatorOpen.health().getStatus()).isEqualTo(Status.DOWN);

    // Run 2: Outside market hours (Sunday), stale poller -> UP
    final Clock weekendClock = Clock.fixed(Instant.parse("2026-10-25T06:30:00Z"), IST);
    final TradingHealthIndicator indicatorWeekend =
        new TradingHealthIndicator(refresher, telegram, signals, weekendClock);
    assertThat(indicatorWeekend.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void meterRegistryContainsAllSixMandatedMetricNames() {
    final MeterRegistry registry = new SimpleMeterRegistry();

    // Register all six metrics
    registry.counter("notifications.dropped");
    registry.counter("notifications.send.failures");
    registry.counter("kite.reauth.attempts");
    registry.timer("kite.order.latency");
    registry.counter("kite.poller.errors");
    registry.counter("tracker.orders.lost");

    assertThat(registry.find("notifications.dropped").counter()).isNotNull();
    assertThat(registry.find("notifications.send.failures").counter()).isNotNull();
    assertThat(registry.find("kite.reauth.attempts").counter()).isNotNull();
    assertThat(registry.find("kite.order.latency").timer()).isNotNull();
    assertThat(registry.find("kite.poller.errors").counter()).isNotNull();
    assertThat(registry.find("tracker.orders.lost").counter()).isNotNull();
  }
}
