package com.trading.bot.notifications.api;

import java.time.Instant;

/** Immutable notification carried through the publisher port. */
public record Notification(
    Severity severity,
    NotificationType type,
    String title,
    String body,
    Throwable cause,
    Instant ts) {

  /** Factory that stamps {@code ts} as now and leaves {@code cause} null. */
  public static Notification of(
      Severity severity, NotificationType type, String title, String body) {
    return new Notification(severity, type, title, body, null, Instant.now());
  }
}
