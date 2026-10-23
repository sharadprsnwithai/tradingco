package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Supported trading exchanges. */
public enum Exchange {
  NSE,
  NFO,
  BFO,
  MCX,
  CDS;

  /**
   * Resolves exchange from raw Kite string.
   *
   * @param raw raw exchange name
   * @return matching Exchange enum
   */
  public static Exchange fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Exchange string must not be blank");
    }
    return Exchange.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
