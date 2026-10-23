package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.model.OrderId;
import java.time.Instant;

/**
 * Immutable view of an order currently tracked by the ExecutionTracker.
 *
 * @param id order ID
 * @param tag correlation tag
 * @param state current execution state
 * @param quantity original quantity
 * @param filled executed fill quantity
 * @param reason rejection, cancellation, or error reason
 * @param submittedAt timestamp when order was registered
 */
public record TrackedOrder(
    OrderId id,
    String tag,
    OrderState state,
    int quantity,
    int filled,
    String reason,
    Instant submittedAt) {}
