package com.trading.bot.broker.api.model;

import java.math.BigDecimal;

/**
 * Market quote snapshot for an instrument.
 *
 * @param token instrument token
 * @param tradingsymbol trading symbol
 * @param lastPrice last traded price
 * @param open day open price
 * @param high day high price
 * @param low day low price
 * @param close previous close price
 * @param volume total traded volume
 */
public record Quote(
    InstrumentToken token,
    String tradingsymbol,
    BigDecimal lastPrice,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume) {}
