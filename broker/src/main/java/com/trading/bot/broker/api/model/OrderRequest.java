package com.trading.bot.broker.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain command to place an order.
 *
 * @param instrumentToken numeric instrument token
 * @param tradingsymbol trading symbol (e.g. RELIANCE)
 * @param exchange exchange
 * @param transactionType BUY or SELL
 * @param orderType MARKET, LIMIT, SL, SL_M
 * @param product CNC, MIS, NRML
 * @param validity DAY or IOC
 * @param quantity positive order quantity
 * @param price optional limit price
 * @param triggerPrice optional trigger price
 * @param tag optional tracking tag
 */
public record OrderRequest(
    InstrumentToken instrumentToken,
    String tradingsymbol,
    Exchange exchange,
    TransactionType transactionType,
    OrderType orderType,
    Product product,
    Validity validity,
    int quantity,
    Optional<BigDecimal> price,
    Optional<BigDecimal> triggerPrice,
    Optional<String> tag) {

  public OrderRequest {
    Objects.requireNonNull(instrumentToken, "instrumentToken must not be null");
    Objects.requireNonNull(exchange, "exchange must not be null");
    Objects.requireNonNull(transactionType, "transactionType must not be null");
    Objects.requireNonNull(orderType, "orderType must not be null");
    Objects.requireNonNull(product, "product must not be null");
    Objects.requireNonNull(validity, "validity must not be null");
    Objects.requireNonNull(price, "price Optional must not be null");
    Objects.requireNonNull(triggerPrice, "triggerPrice Optional must not be null");
    Objects.requireNonNull(tag, "tag Optional must not be null");

    if (tradingsymbol == null || tradingsymbol.isBlank()) {
      throw new IllegalArgumentException("tradingsymbol must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive, got " + quantity);
    }
    if (orderType == OrderType.LIMIT && price.isEmpty()) {
      throw new IllegalArgumentException("LIMIT order requires price");
    }
    if ((orderType == OrderType.SL || orderType == OrderType.SL_M) && triggerPrice.isEmpty()) {
      throw new IllegalArgumentException("SL/SL_M order requires triggerPrice");
    }
  }
}
