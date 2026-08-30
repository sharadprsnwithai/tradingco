package com.tradingbot.model;

import com.tradingbot.model.enums.OrderType;

import java.math.BigDecimal;

/**
 * Represents a request to modify an existing order (e.g. change quantity, price, or order type).
 *
 * @param orderId        the internal order ID
 * @param brokerOrderId  the broker-assigned order ID to modify
 * @param quantity       the new quantity
 * @param price          the new limit price
 * @param triggerPrice   the new trigger price (for SL orders)
 * @param orderType      the new order type (e.g. LIMIT, MARKET)
 */
public record OrderModifyRequest(
    String orderId,
    String brokerOrderId,
    int quantity,
    BigDecimal price,
    BigDecimal triggerPrice,
    OrderType orderType,
    String symbol,
    String exchange
) {
    public OrderModifyRequest(
        String orderId,
        String brokerOrderId,
        int quantity,
        BigDecimal price,
        BigDecimal triggerPrice,
        OrderType orderType
    ) {
        this(orderId, brokerOrderId, quantity, price, triggerPrice, orderType, null, "NFO");
    }
}
