package com.tradingbot.strategy;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Tick;

import java.util.List;

/**
 * Pluggable, decoupled Strategy SPI interface.
 * Strategies contain zero broker-specific or order routing logic.
 * They consume market events (ticks, candles, schedules) and emit immutable Signals.
 */
public interface Strategy {

    /**
     * Unique identifier for this strategy instance (e.g., "VANDE_BHARAT_KITE_01").
     */
    String getStrategyId();

    /**
     * Broker account ID bound 1:1 to this strategy.
     */
    String getAssignedAccountId();

    /**
     * List of canonical symbols this strategy monitors and trades (e.g. ["NSE:RELIANCE"]).
     */
    List<String> getSubscribedSymbols();

    /**
     * Lifecycle initialization with injected execution context.
     */
    void init(StrategyContext context);

    /**
     * Real-time intra-candle tick event handler for high-frequency stop-loss and trailing execution.
     */
    void onTick(Tick tick);

    /**
     * Closed candle event handler for indicator evaluations (EMA, Supertrend, PDH/PDL, Inside Candle).
     */
    void onCandle(Candle candle);

    /**
     * Scheduled clock event handler (e.g. Market Open, Pre-Market Scan, 15:15 Cutoff).
     */
    void onSchedule(ScheduledEvent event);

    /**
     * Lifecycle cleanup on shutdown or strategy deactivation.
     */
    void destroy();

    /**
     * Check if this strategy is active.
     */
    boolean isEnabled();

    /**
     * Enable or pause this strategy dynamically (e.g. from Kill Switch or Telegram).
     */
    void setEnabled(boolean enabled);
}
