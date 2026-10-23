package com.trading.bot.broker.api.model;

import java.math.BigDecimal;

/**
 * Portfolio position snapshot.
 *
 * @param exchange exchange
 * @param tradingsymbol trading symbol
 * @param product product type
 * @param quantity net quantity
 * @param overnight overnight quantity
 * @param multiplier lot size multiplier
 * @param averagePrice average entry price
 * @param pnl unrealized / realized profit and loss
 */
public record Position(
    Exchange exchange,
    String tradingsymbol,
    Product product,
    int quantity,
    int overnight,
    int multiplier,
    BigDecimal averagePrice,
    BigDecimal pnl) {}
