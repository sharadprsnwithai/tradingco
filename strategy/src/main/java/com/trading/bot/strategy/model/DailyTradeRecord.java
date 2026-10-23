package com.trading.bot.strategy.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable daily persistence record capturing the active trade state across all 4 legs.
 *
 * @param date trading date
 * @param index target index
 * @param state current strategy state
 * @param hedgeCe long call hedge wing
 * @param hedgePe long put hedge wing
 * @param shortCe short call straddle leg
 * @param shortPe short put straddle leg
 * @param realizedPnl cumulative realized P&L for the day
 */
public record DailyTradeRecord(
    LocalDate date,
    TargetIndex index,
    StrategyState state,
    TradeLeg hedgeCe,
    TradeLeg hedgePe,
    TradeLeg shortCe,
    TradeLeg shortPe,
    BigDecimal realizedPnl) {}
