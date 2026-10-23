package com.trading.bot.broker.health;

import com.trading.bot.broker.kite.MarketHours;
import com.trading.bot.broker.kite.SessionRefresher;
import com.trading.bot.notifications.internal.TelegramSender;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * HealthIndicator combining Kite authentication, notification transport, and scheduler freshness.
 */
@Component
public final class TradingHealthIndicator implements HealthIndicator {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final long MAX_STALE_SECONDS = 60L;
  private static final int MAX_TELEGRAM_CONSECUTIVE_FAILURES = 3;

  private final SessionRefresher refresher;
  private final TelegramSender telegramSender;
  private final RuntimeSignals signals;
  private final Clock clock;

  @Autowired
  public TradingHealthIndicator(
      SessionRefresher refresher, TelegramSender telegramSender, RuntimeSignals signals) {
    this(refresher, telegramSender, signals, Clock.system(IST));
  }

  public TradingHealthIndicator(
      SessionRefresher refresher,
      TelegramSender telegramSender,
      RuntimeSignals signals,
      Clock clock) {
    this.refresher = Objects.requireNonNull(refresher, "refresher must not be null");
    this.telegramSender = Objects.requireNonNull(telegramSender, "telegramSender must not be null");
    this.signals = Objects.requireNonNull(signals, "signals must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Health health() {
    final boolean authOk = !refresher.isDegraded();
    final boolean notifOk =
        telegramSender.consecutiveFailures() < MAX_TELEGRAM_CONSECUTIVE_FAILURES;

    final boolean marketOpen = MarketHours.isOpen(clock.instant(), IST);
    final boolean schedulersOk;
    final String schedulersDetail;

    if (marketOpen) {
      final long nowSec = clock.instant().getEpochSecond();
      final long lastRun = signals.lastPollerRun();
      final long diff = nowSec - lastRun;
      if (lastRun <= 0 || diff > MAX_STALE_SECONDS) {
        schedulersOk = false;
        schedulersDetail = "DOWN (poller stale: " + diff + "s)";
      } else {
        schedulersOk = true;
        schedulersDetail = "UP";
      }
    } else {
      schedulersOk = true;
      schedulersDetail = "UP (market closed)";
    }

    final boolean overallOk = authOk && notifOk && schedulersOk;
    final Health.Builder builder = overallOk ? Health.up() : Health.down();

    builder.withDetail("kiteAuth", authOk ? "UP" : "DOWN");
    builder.withDetail(
        "notifications",
        notifOk ? "UP" : "DOWN (failures=" + telegramSender.consecutiveFailures() + ")");
    builder.withDetail("schedulers", schedulersDetail);

    return builder.build();
  }
}
