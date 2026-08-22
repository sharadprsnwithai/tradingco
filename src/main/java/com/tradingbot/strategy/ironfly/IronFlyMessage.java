package com.tradingbot.strategy.ironfly;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Holds the data needed to format an Iron Fly Telegram message.
 */
public record IronFlyMessage(
    IronFlyMessageType type,
    String underlying,
    IronFlyPosition position,
    String additionalInfo,
    Instant timestamp
) {}
