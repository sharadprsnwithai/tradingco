package com.tradingbot.model.enums;

/**
 * Represents the booking type for an order or position.
 * Differentiates between intraday trades (square-off same day) and positional trades (held across days).
 */
public enum BookType {
    /** Intraday trade that must be squared off within the same trading session. */
    INTRADAY,
    /** Positional trade that can be held overnight or for multiple days. */
    POSITIONAL
}
