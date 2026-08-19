package com.tradingbot.strategy;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Context provided to an executing strategy for non-blocking signal emission
 * and historical candle buffer inspection. Decouples strategy logic from brokers and OMS.
 */
public interface StrategyContext {

    /**
     * Unique ID of the owning strategy instance.
     */
    String getStrategyId();

    /**
     * Account ID bound to this strategy (e.g. Kite User ID or Shoonya User ID).
     */
    String getAssignedAccountId();

    /**
     * Emits a trading signal (ENTRY, EXIT, CANCEL, PARTIAL) to the Strategy Engine pipeline.
     */
    void emitSignal(Signal signal);

    /**
     * Returns the most recent completed candle for the specified symbol and timeframe.
     */
    Optional<Candle> getLastCandle(String symbol, String timeframe);

    /**
     * Returns the last N completed candles for the specified symbol and timeframe.
     */
    List<Candle> getHistoricalCandles(String symbol, String timeframe, int count);

    /**
     * Returns primitive array of close prices for high-performance indicator computation.
     */
    double[] getClosePrices(String symbol, String timeframe);

    /**
     * Current clock time (supports live time or simulated historical backtest time).
     */
    Instant now();
}
