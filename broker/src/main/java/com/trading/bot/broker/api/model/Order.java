package com.trading.bot.broker.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Immutable domain representation of an order in the Kite order book.
 *
 * @param orderId unique order ID
 * @param tag optional correlation tag
 * @param exchange exchange
 * @param tradingsymbol trading symbol
 * @param transactionType BUY or SELL
 * @param orderType MARKET, LIMIT, SL, SL_M
 * @param product margin product
 * @param validity order validity
 * @param status mapped domain OrderStatus
 * @param rawStatus raw Kite status string
 * @param quantity original order quantity
 * @param filledQuantity executed quantity
 * @param pendingQuantity unexecuted open quantity
 * @param price limit price
 * @param triggerPrice stoploss trigger price
 * @param averagePrice average execution fill price
 * @param statusMessage rejection or error message from exchange
 * @param orderTimestamp placement timestamp
 */
public record Order(
    OrderId orderId,
    Optional<String> tag,
    Exchange exchange,
    String tradingsymbol,
    TransactionType transactionType,
    OrderType orderType,
    Product product,
    Validity validity,
    OrderStatus status,
    String rawStatus,
    int quantity,
    int filledQuantity,
    int pendingQuantity,
    BigDecimal price,
    BigDecimal triggerPrice,
    BigDecimal averagePrice,
    String statusMessage,
    Instant orderTimestamp) {}
