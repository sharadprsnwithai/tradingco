package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.model.OrderId;
import com.trading.bot.broker.api.model.OrderStatus;
import java.time.Instant;

/**
 * Point-in-time observation of an order from the Kite order book.
 *
 * @param id order ID
 * @param status mapped domain OrderStatus
 * @param quantity original quantity
 * @param filled filled quantity
 * @param reason rejection or cancellation reason string
 * @param observedAt observation timestamp
 */
public record OrderSnapshot(
    OrderId id, OrderStatus status, int quantity, int filled, String reason, Instant observedAt) {}
