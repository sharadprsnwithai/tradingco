package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Standardized order execution states mapped from Kite order statuses. */
public enum OrderStatus {
  OPEN,
  PENDING,
  FILLED,
  PARTIAL,
  CANCELLED,
  REJECTED,
  EXPIRED,
  UNKNOWN;

  /**
   * Maps raw Kite status string to standardized domain OrderStatus.
   *
   * @param raw raw status string from Kite
   * @return mapped OrderStatus
   */
  public static OrderStatus fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }

    final String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "OPEN", "TRIGGER PENDING" -> OPEN;
      case "COMPLETE" -> FILLED;
      case "CANCELLED" -> CANCELLED;
      case "REJECTED" -> REJECTED;
      case "EXPIRED" -> EXPIRED;
      case "OPEN PENDING", "PUT ORDER REQ RECEIVED", "MODIFY REQ RECEIVED", "CANCEL REQ RECEIVED" ->
          PENDING;
      default -> UNKNOWN;
    };
  }

  /**
   * Returns true if status represents an immutable terminal state.
   *
   * @param status status to check
   * @return true if terminal
   */
  public static boolean isTerminal(OrderStatus status) {
    if (status == null) {
      return false;
    }
    return switch (status) {
      case FILLED, CANCELLED, REJECTED, EXPIRED -> true;
      case OPEN, PENDING, PARTIAL, UNKNOWN -> false;
    };
  }
}
