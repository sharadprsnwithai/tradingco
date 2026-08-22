package com.tradingbot.strategy.ironfly;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Records a single adjustment event on an Iron Fly position.
 */
public record AdjustmentRecord(
    AdjustmentSide side,
    Instant adjustedAt,
    int oldShortStrike,
    int newShortStrike,
    int oldLongStrike,
    int newLongStrike,
    BigDecimal creditDelta,
    String reason
) {}
