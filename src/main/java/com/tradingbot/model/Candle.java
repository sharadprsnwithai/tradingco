package com.tradingbot.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents an OHLCV (Open, High, Low, Close, Volume) candlestick for a given symbol and timeframe.
 *
 * @param symbol    the trading symbol (e.g. "NIFTY", "BANKNIFTY")
 * @param timeframe the candle timeframe (e.g. "1m", "5m", "15m")
 * @param timestamp the candle opening timestamp
 * @param open      the opening price
 * @param high      the highest price during the candle period
 * @param low       the lowest price during the candle period
 * @param close     the closing price
 * @param volume    the total volume traded during the candle period
 */
public record Candle(
    String symbol,
    String timeframe,
    Instant timestamp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume
) {}
