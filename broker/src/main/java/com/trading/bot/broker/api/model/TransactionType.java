package com.trading.bot.broker.api.model;

import java.util.Locale;

/** Buy or sell transaction side. */
public enum TransactionType {
  BUY,
  SELL;

  /**
   * Resolves transaction type from raw Kite string.
   *
   * @param raw raw transaction type
   * @return matching TransactionType
   */
  public static TransactionType fromKite(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("TransactionType string must not be blank");
    }
    return TransactionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }
}
