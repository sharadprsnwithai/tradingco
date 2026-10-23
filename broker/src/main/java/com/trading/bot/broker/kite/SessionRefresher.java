package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Manages daily cron refresh, re-auth replay backoff, and DEGRADED state machine. */
@Component
public final class SessionRefresher implements SessionDelegate, ApplicationRunner {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(SessionRefresher.class);
  private static final Duration ONE_HOUR = Duration.ofHours(1);

  private final KiteSession session;
  private final KiteProperties.Auth authProps;
  private final NotificationPublisher publisher;
  private final Counter reauthCounter;
  private final Clock clock;

  private final List<Instant> attemptHistory = new ArrayList<>();
  private int consecutiveFailures;
  private Instant nextAllowedAttemptTime = Instant.EPOCH;
  private volatile boolean degraded;
  private final Object lock = new Object();

  @Autowired
  public SessionRefresher(
      KiteSession session,
      KiteProperties props,
      NotificationPublisher publisher,
      MeterRegistry registry) {
    this(session, props.auth(), publisher, registry, Clock.system(ZoneId.of("Asia/Kolkata")));
  }

  public SessionRefresher(
      KiteSession session,
      KiteProperties.Auth authProps,
      NotificationPublisher publisher,
      MeterRegistry registry,
      Clock clock) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.authProps = Objects.requireNonNull(authProps, "authProps must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(registry, "registry must not be null");

    this.reauthCounter = registry.counter("kite.reauth.attempts");
  }

  @Override
  public void run(ApplicationArguments args) {
    loginOnStartup();
  }

  /** Fail-safe initial login at application startup per spec §7, entering DEGRADED on failure. */
  public void loginOnStartup() {
    try {
      session.login();
    } catch (AuthException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Initial startup login failed: {}", e.getMessage(), e);
      }
      synchronized (lock) {
        this.degraded = true;
        session.setDegraded(true);
        consecutiveFailures = 1;
        final Duration backoff = computeBackoff(consecutiveFailures);
        nextAllowedAttemptTime = clock.instant().plus(backoff);
      }
      publisher.publish(
          Notification.of(
              Severity.CRITICAL,
              NotificationType.AUTH_DEGRADED,
              "Auth degraded",
              "Initial startup login failed: " + e.getMessage()));
    }
  }

  /** Daily scheduled re-authentication at 06:00 IST. */
  @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata")
  public void scheduledRefresh() {
    reauthCounter.increment();
    session.login();
  }

  @Override
  public String accessToken() {
    return session.accessToken();
  }

  /**
   * Returns whether session is in a degraded authentication state.
   *
   * @return true if degraded
   */
  public boolean isDegraded() {
    return degraded;
  }

  @Override
  public void forceRelogin() {
    synchronized (lock) {
      final Instant now = clock.instant();
      pruneOldAttempts(now);

      if (attemptHistory.size() >= authProps.maxAttemptsPerHour()) {
        this.degraded = true;
        session.setDegraded(true);
        throw new AuthException(
            "DEGRADED: Maximum re-login attempts per hour ("
                + authProps.maxAttemptsPerHour()
                + ") reached");
      }

      if (now.isBefore(nextAllowedAttemptTime)) {
        this.degraded = true;
        session.setDegraded(true);
        throw new AuthException(
            "DEGRADED: Re-login backoff in effect until " + nextAllowedAttemptTime);
      }

      attemptHistory.add(now);
      reauthCounter.increment();

      try {
        session.login();
        consecutiveFailures = 0;
        nextAllowedAttemptTime = Instant.EPOCH;

        if (this.degraded) {
          this.degraded = false;
          session.setDegraded(false);
          publisher.publish(
              Notification.of(
                  Severity.INFO,
                  NotificationType.AUTH_RECOVERED,
                  "Auth recovered",
                  "Kite session recovered successfully at " + now));
        }
      } catch (AuthException e) {
        consecutiveFailures++;
        this.degraded = true;
        session.setDegraded(true);
        final Duration backoff = computeBackoff(consecutiveFailures);
        nextAllowedAttemptTime = now.plus(backoff);

        publisher.publish(
            Notification.of(
                Severity.CRITICAL,
                NotificationType.AUTH_DEGRADED,
                "Auth degraded",
                "Kite auth DEGRADED: attempt "
                    + consecutiveFailures
                    + " failed at "
                    + now
                    + "; next retry after "
                    + backoff.toSeconds()
                    + "s"));

        throw new AuthException(
            "Kite re-login attempt " + consecutiveFailures + " failed: " + e.getMessage(), e);
      }
    }
  }

  /** Background scheduled retry if session is degraded and due for attempt. */
  @Scheduled(fixedDelayString = "5000")
  public void retryIfDue() {
    synchronized (lock) {
      if (!isDegraded()) {
        return;
      }

      final Instant now = clock.instant();
      pruneOldAttempts(now);

      if (attemptHistory.size() < authProps.maxAttemptsPerHour()
          && !now.isBefore(nextAllowedAttemptTime)) {
        try {
          forceRelogin();
        } catch (AuthException ignored) {
          // Failure handled and recorded in forceRelogin()
        }
      }
    }
  }

  private void pruneOldAttempts(Instant now) {
    final Instant cutoff = now.minus(ONE_HOUR);
    attemptHistory.removeIf(timestamp -> timestamp.isBefore(cutoff));
  }

  private Duration computeBackoff(int failures) {
    final int shift = Math.min(failures - 1, 30);
    final long backoffMillis = authProps.initialRetry().toMillis() * (1L << shift);
    return Duration.ofMillis(Math.min(backoffMillis, authProps.maxRetry().toMillis()));
  }
}
