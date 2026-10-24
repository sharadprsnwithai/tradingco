package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class SessionRefresherTest {

  private static final String KOLKATA_ZONE = "Asia/Kolkata";

  private final KiteProperties.Auth authProps =
      new KiteProperties.Auth(Duration.ofMinutes(1), Duration.ofMinutes(20), 3);

  @Test
  void backoffScheduleEnforcesOneMinuteThenTwoMinutesThenRecovers() {
    final KiteSession session = mock(KiteSession.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final long[] currentEpochSec = new long[] {10_000L};
    final Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of(KOLKATA_ZONE);
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

    final SessionRefresher refresher =
        new SessionRefresher(session, authProps, publisher, registry, clock);

    // Attempt 1 at t=0 fails
    doThrow(new AuthException("Kite auth down")).when(session).login();
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(1)).login();
    assertThat(refresher.isDegraded()).isTrue();

    // Immediate attempt at t=30s fails fast without calling session.login()
    currentEpochSec[0] += 30L;
    assertThatThrownBy(refresher::forceRelogin)
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("DEGRADED");
    verify(session, times(1)).login(); // still 1

    // Attempt 2 at t=60s (1m backoff elapsed) fails -> next backoff is 2m
    currentEpochSec[0] += 30L; // now t=60s
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(2)).login();

    // Immediate attempt at t=120s fails fast (2m backoff not yet elapsed)
    currentEpochSec[0] += 60L; // now t=120s
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(2)).login();

    // Window rolling / attempt 3 at t=180s (2m elapsed from attempt 2 at 60s)
    currentEpochSec[0] += 60L; // now t=180s
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(3)).login();

    // Now roll window past 1 hour (t0 + 3601s) and succeed on 4th
    currentEpochSec[0] = 10_000L + 3601L;
    org.mockito.Mockito.doNothing().when(session).login();
    refresher.forceRelogin();

    verify(session, times(4)).login();
    assertThat(refresher.isDegraded()).isFalse();

    final boolean hasRecoveredAlert =
        published.stream().anyMatch(n -> n.type() == NotificationType.AUTH_RECOVERED);
    assertThat(hasRecoveredAlert).isTrue();
  }

  @Test
  void exactlyThreeAttemptsInsideOneHourBlocksFourthLoginCall() {
    final KiteSession session = mock(KiteSession.class);
    final NotificationPublisher publisher = n -> {};
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final long[] currentEpochSec = new long[] {10_000L};
    final Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of(KOLKATA_ZONE);
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

    final SessionRefresher refresher =
        new SessionRefresher(session, authProps, publisher, registry, clock);

    doThrow(new AuthException("Kite fail")).when(session).login();

    // Attempt 1 at t=0
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    // Attempt 2 at t=60s
    currentEpochSec[0] += 60L;
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    // Attempt 3 at t=180s
    currentEpochSec[0] += 120L;
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);

    verify(session, times(3)).login();

    // Attempt 4 at t=300s (inside same hour): hard hourly cap is 3!
    currentEpochSec[0] += 120L;
    assertThatThrownBy(refresher::forceRelogin)
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("DEGRADED");

    // Zero further KiteSession.login() invocations! Never a 4th login
    verify(session, times(3)).login();
  }

  @Test
  void rollingSixtyMinuteWindowAllowsAttemptsAgain() {
    final KiteSession session = mock(KiteSession.class);
    final NotificationPublisher publisher = n -> {};
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final long[] currentEpochSec = new long[] {10_000L};
    final Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of(KOLKATA_ZONE);
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

    final SessionRefresher refresher =
        new SessionRefresher(session, authProps, publisher, registry, clock);

    doThrow(new AuthException("Kite fail")).when(session).login();

    // 3 attempts inside 3 minutes
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    currentEpochSec[0] += 60L;
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    currentEpochSec[0] += 120L;
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(3)).login();

    // Advance clock past 61 minutes
    currentEpochSec[0] = 10_000L + 3661L;

    // Window rolled -> attempt allowed
    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);
    verify(session, times(4)).login();
  }

  @Test
  void startupLoginFailurePublishesDegradedAndRethrows() {
    final KiteSession session = mock(KiteSession.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T11:30:00Z"), ZoneId.of(KOLKATA_ZONE));

    final SessionRefresher refresher =
        new SessionRefresher(session, authProps, publisher, registry, clock);

    doThrow(new AuthException("Bad credentials")).when(session).login();

    // Spec §7 / ruling R3: fail-fast — no trading loop without a session
    assertThatThrownBy(refresher::loginOnStartup).isInstanceOf(AuthException.class);

    final List<Notification> degradedAlerts =
        published.stream().filter(n -> n.type() == NotificationType.AUTH_DEGRADED).toList();
    assertThat(degradedAlerts).hasSize(1);
    assertThat(degradedAlerts.get(0).severity()).isEqualTo(Severity.CRITICAL);
    assertThat(refresher.isDegraded()).isTrue();
  }

  @Test
  void authDegradedNotificationCarriesAttemptNumberAndTimestamp() {
    final KiteSession session = mock(KiteSession.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse("2026-10-23T11:30:00Z"), ZoneId.of(KOLKATA_ZONE));

    final SessionRefresher refresher =
        new SessionRefresher(session, authProps, publisher, registry, clock);

    doThrow(new AuthException("Bad credentials")).when(session).login();

    assertThatThrownBy(refresher::forceRelogin).isInstanceOf(AuthException.class);

    final Notification degradedAlert =
        published.stream()
            .filter(n -> n.type() == NotificationType.AUTH_DEGRADED)
            .findFirst()
            .orElseThrow();

    assertThat(degradedAlert.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(degradedAlert.body()).contains("attempt 1");
    assertThat(degradedAlert.body()).contains("2026-10-23");
  }
}
