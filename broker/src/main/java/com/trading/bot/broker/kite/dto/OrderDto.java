package com.trading.bot.broker.kite.dto;

import java.math.BigDecimal;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Raw order representation in the Kite order book response.
 *
 * @param orderId order identifier
 * @param tag order tag
 * @param exchange exchange
 * @param tradingsymbol trading symbol
 * @param transactionType BUY or SELL
 * @param orderType MARKET, LIMIT, SL, SL_M
 * @param product CNC, MIS, NRML
 * @param validity DAY, IOC
 * @param status raw Kite status
 * @param quantity original quantity
 * @param filledQuantity filled quantity
 * @param pendingQuantity pending quantity
 * @param price limit price
 * @param triggerPrice trigger price
 * @param averagePrice average fill price
 * @param statusMessage exchange status message
 * @param orderTimestamp timestamp string
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderDto(
    String orderId,
    String tag,
    String exchange,
    String tradingsymbol,
    String transactionType,
    String orderType,
    String product,
    String validity,
    String status,
    int quantity,
    int filledQuantity,
    int pendingQuantity,
    BigDecimal price,
    BigDecimal triggerPrice,
    BigDecimal averagePrice,
    String statusMessage,
    String orderTimestamp) {}
