package com.trading.bot.notifications.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Contract tests for the notification port: factory defaults, value equality, type taxonomy. */
// Multiple asserts per test keep each contract readable in one place; splitting would obscure it.
@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class NotificationApiTest {

  @Test
  void ofStampsNowAndNullCause() {
    final Instant before = Instant.now();
    final Notification n =
        Notification.of(Severity.WARN, NotificationType.SYSTEM_ALERT, "title", "body");
    final Instant after = Instant.now();

    assertNull(n.cause(), "factory leaves cause null");
    assertNotNull(n.ts(), "factory stamps ts");
    assertFalse(n.ts().isBefore(before), "ts not before now - " + n.ts());
    assertFalse(n.ts().isAfter(after), "ts not after now - " + n.ts());
    assertEquals(Severity.WARN, n.severity(), "severity passes through");
    assertEquals(NotificationType.SYSTEM_ALERT, n.type(), "type passes through");
    assertEquals("title", n.title(), "title passes through");
    assertEquals("body", n.body(), "body passes through");
  }

  @Test
  void recordHasCanonicalEquality() {
    final Throwable cause = new IllegalStateException("boom");
    final Instant ts = Instant.parse("2026-10-23T00:00:00Z");
    final Notification a =
        new Notification(Severity.INFO, NotificationType.STARTUP, "t", "b", cause, ts);
    final Notification b =
        new Notification(Severity.INFO, NotificationType.STARTUP, "t", "b", cause, ts);

    assertEquals(a, b, "equal components mean equal notifications");
    assertEquals(a.hashCode(), b.hashCode(), "equal notifications share hashCode");
  }

  @Test
  void notificationTypeHasAllThirteenConstants() {
    assertEquals(13, NotificationType.values().length, "taxonomy must not shrink");
    assertArrayEquals(
        new String[] {
          "STARTUP",
          "SHUTDOWN",
          "ORDER_SUBMITTED",
          "ORDER_PARTIAL",
          "ORDER_FILLED",
          "ORDER_REJECTED",
          "ORDER_CANCELLED",
          "ORDER_LOST",
          "ORDER_AMBIGUOUS",
          "ORDER_RECOVERED",
          "AUTH_DEGRADED",
          "AUTH_RECOVERED",
          "SYSTEM_ALERT"
        },
        java.util.Arrays.stream(NotificationType.values()).map(Enum::name).toArray(String[]::new),
        "every NotificationType constant exists");
  }
}
