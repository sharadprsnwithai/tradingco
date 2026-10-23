package com.trading.bot.broker.api.model;

import java.math.BigDecimal;

/**
 * Portfolio equity holding snapshot.
 *
 * @param exchange exchange
 * @param tradingsymbol trading symbol
 * @param quantity total quantity
 * @param t1Quantity unsettled T+1 quantity
 * @param averagePrice average acquisition price
 * @param lastPrice current market price
 * @param pnl unrealized profit and loss
 */
public record Holding(
    Exchange exchange,
    String tradingsymbol,
    int quantity,
    int t1Quantity,
    BigDecimal averagePrice,
    BigDecimal lastPrice,
    BigDecimal pnl) {}
