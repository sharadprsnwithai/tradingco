package com.tradingbot.strategy.ironfly;

/**
 * Lifecycle status of an Iron Fly position.
 */
public enum IronFlyStatus {
    /** Bot has sent recommendation, waiting for user to execute */
    RECOMMENDED,
    /** Legs discovered in broker positions */
    DISCOVERED,
    /** Position is actively tracked with daily evaluation */
    TRACKING,
    /** At least one adjustment has been applied */
    ADJUSTED,
    /** Position has been closed */
    CLOSED
}
