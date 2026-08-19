package com.tradingbot.model.enums;

/**
 * Represents the product type for an order, determining margin requirements and settlement.
 */
public enum ProductType {
    /** Margin Intraday Square-off - intraday product with leverage, auto-squared off at EOD. */
    MIS,
    /** Normal - positional product for delivery-based or carry-forward trades. */
    NRML,
    /** Cash and Carry - CNC product for equity delivery trades settled with T+1 cycle. */
    CNC
}
