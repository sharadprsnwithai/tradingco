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
 * @param slPrice stop loss threshold price
 * @param isOpen true if leg is actively open
 */
public record TradeLeg(
    String symbol,
    InstrumentToken token,
    int quantity,
    BigDecimal entryPrice,
    BigDecimal slPrice,
    boolean isOpen) {}
