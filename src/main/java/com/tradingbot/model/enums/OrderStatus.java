package com.tradingbot.model.enums;

/**
 * Represents the lifecycle status of an order.
 */
public enum OrderStatus {
    /** Order has been created but not yet submitted to the broker. */
    PENDING,
    /** Order has been accepted by the broker and is active in the market. */
    OPEN,
    /** Stop-loss order is pending its trigger price to be reached. */
    TRIGGER_PENDING,
    /** Order has been partially executed with some quantity remaining. */
    PARTIALLY_FILLED,
    /** Order has been fully executed. */
    FILLED,
    /** Order has been cancelled by the user or system before full execution. */
    CANCELLED,
    /** Order has been rejected by the broker or risk management system. */
    REJECTED
}
