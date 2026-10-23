package com.trading.bot.notifications.api;

/**
 * Taxonomy of events a notification describes; kept closed so consumers can switch exhaustively.
 */
public enum NotificationType {
  STARTUP,
  SHUTDOWN,
  ORDER_SUBMITTED,
  ORDER_PARTIAL,
  ORDER_FILLED,
  ORDER_REJECTED,
  ORDER_CANCELLED,
  ORDER_LOST,
  ORDER_AMBIGUOUS,
  ORDER_RECOVERED,
  AUTH_DEGRADED,
  AUTH_RECOVERED,
  SYSTEM_ALERT
}
