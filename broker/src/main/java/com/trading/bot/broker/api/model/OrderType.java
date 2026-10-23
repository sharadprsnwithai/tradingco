package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Order execution types. */
public enum OrderType {
  MARKET("MARKET"),
  LIMIT("LIMIT"),
  SL("SL"),
  SL_M("SL M");

  private final String wireValue;

  OrderType(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * Returns wire string representation expected by Kite API.
   *
   * @return wire string value
   */
  public String toKite() {
    return wireValue;
  }

  /**
   * Resolves order type from raw Kite wire string.
   *
   * @param raw wire string
   * @return matching OrderType
   */
  public static OrderType fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("OrderType string must not be blank");
    }
    final String trimmed = raw.trim().toUpperCase(Locale.ROOT);
    if ("SL M".equals(trimmed) || "SL-M".equals(trimmed) || "SL_M".equals(trimmed)) {
      return SL_M;
    }
    return OrderType.valueOf(trimmed);
  }
}
