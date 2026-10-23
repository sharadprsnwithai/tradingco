package com.trading.bot.broker.api.model;

/**
 * Compound key required by Kite quote queries containing exchange, symbol, and token.
 *
 * @param exchange exchange
 * @param tradingsymbol trading symbol
 * @param token instrument token
 */
public record QuoteRef(Exchange exchange, String tradingsymbol, InstrumentToken token) {}
