package com.tradingbot.strategy.ironfly;

/**
 * Telegram message types for Iron Fly position lifecycle.
 */
public enum IronFlyMessageType {
    ENTRY_RECOMMENDATION,
    POSITION_DISCOVERED,
    DAILY_STATUS,
    ADJUSTMENT_ALERT,
    EXIT_ALERT,
    CLOSE_SUMMARY
}
