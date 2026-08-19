package com.tradingbot.model.enums;

/**
 * Represents the type of order to be placed with the broker.
 */
public enum OrderType {
    /** Market order executed at the best available current price. */
    MARKET,
    /** Limit order executed only at the specified price or better. */
    LIMIT,
    /** Stop-loss market order that triggers a market order when the trigger price is reached. */
    SL_M,
    /** Stop-loss limit order that triggers a limit order when the trigger price is reached. */
    SL_L
}
