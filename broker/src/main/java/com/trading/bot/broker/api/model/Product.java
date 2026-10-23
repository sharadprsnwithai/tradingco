package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Margin product types. */
public enum Product {
  CNC,
  MIS,
  NRML;

  /**
   * Resolves product from raw Kite string.
   *
   * @param raw raw product
   * @return matching Product
   */
  public static Product fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("Product string must not be blank");
    }
    return Product.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
