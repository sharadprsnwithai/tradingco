package com.trading.bot.strategy.model;

import com.trading.bot.broker.api.model.InstrumentToken;
import java.math.BigDecimal;

/**
 * Snapshot of an individual option leg (hedge wing or straddle short).
 *
 * @param symbol tradingsymbol
 * @param token instrument token
 * @param quantity position quantity
 * @param entryPrice fill premium price
 * @param exitPrice execution exit price (or zero if still open)
 * @param slPrice stop loss threshold price
 * @param isOpen true if leg is actively open
 */
public record TradeLeg(
    String symbol,
    InstrumentToken token,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal exitPrice,
    BigDecimal slPrice,
    boolean isOpen) {

  public TradeLeg(
      String symbol,
      InstrumentToken token,
      int quantity,
      BigDecimal entryPrice,
      BigDecimal slPrice,
      boolean isOpen) {
    this(symbol, token, quantity, entryPrice, BigDecimal.ZERO, slPrice, isOpen);
  }

  /**
   * Computes realized P&L for this leg based on entry and exit prices.
   *
   * @param isShort true for short option legs, false for long hedge wings
   * @return realized P&L in currency
   */
  public BigDecimal calculatePnl(boolean isShort) {
    if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    final BigDecimal diff =
        isShort ? entryPrice.subtract(exitPrice) : exitPrice.subtract(entryPrice);
    return diff.multiply(BigDecimal.valueOf(quantity));
  }
}
