package com.tradingbot.model;

import com.tradingbot.model.enums.OrderStatus;

/**
 * Represents the result of an order placement or modification attempt.
 *
 * @param brokerOrderId  the broker-assigned order ID (null on failure)
 * @param localOrderId   the internal order ID
 * @param status         the resulting order status
 * @param message        an optional message (e.g. error description on failure)
 * @param success        whether the operation succeeded
 */
public record OrderResult(
    String brokerOrderId,
    String localOrderId,
    OrderStatus status,
    String message,
    boolean success
) {
    /**
     * Creates a successful {@link OrderResult}.
     *
     * @param brokerOrderId the broker-assigned order ID
     * @param localOrderId  the internal order ID
     * @param status        the resulting order status
     * @return a successful OrderResult
     */
    public static OrderResult success(String brokerOrderId, String localOrderId, OrderStatus status) {
        return new OrderResult(brokerOrderId, localOrderId, status, null, true);
    }

    /**
     * Creates a failed {@link OrderResult} with a rejection status.
     *
     * @param localOrderId the internal order ID
     * @param message      the failure reason
     * @return a failed OrderResult
     */
    public static OrderResult failure(String localOrderId, String message) {
        return new OrderResult(null, localOrderId, OrderStatus.REJECTED, message, false);
    }
}
