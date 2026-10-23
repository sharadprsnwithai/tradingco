package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Order duration validity. */
public enum Validity {
  DAY,
  IOC;

  /**
   * Resolves validity from raw Kite string.
   *
   * @param raw raw validity
   * @return matching Validity
   */
  public static Validity fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Validity string must not be blank");
    }
    return Validity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
