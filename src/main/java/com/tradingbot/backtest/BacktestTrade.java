package com.tradingbot.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestTrade(
    String symbol,
    String direction,
    Instant entryTime,
    BigDecimal entryPrice,
    Instant exitTime,
    BigDecimal exitPrice,
    int quantity,
    BigDecimal pnl,
    BigDecimal pnlPercent,
    String entryTag,
    String exitTag
) {}
