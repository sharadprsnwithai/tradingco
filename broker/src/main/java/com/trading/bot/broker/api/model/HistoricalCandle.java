package com.trading.bot.broker.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Historical OHLC candle record from Kite Connect historical data API.
 *
 * @param timestamp candle timestamp
 * @param open open price
 * @param high high price
 * @param low low price
 * @param close close price
 * @param volume traded volume
 */
public record HistoricalCandle(
    Instant timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume) {}
