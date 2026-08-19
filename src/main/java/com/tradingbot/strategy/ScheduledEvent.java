package com.tradingbot.strategy;

import java.time.Instant;

/**
 * Immutable representation of a scheduled market clock event dispatched to strategies.
 * Defines standard event types for the Indian market trading session lifecycle.
 *
 * @param eventType the type of scheduled event (e.g., {@link #MARKET_OPEN})
 * @param timestamp the instant at which this event was created
 */
public record ScheduledEvent(
    String eventType,
    Instant timestamp
) {
    public static final String PRE_MARKET_SCAN = "PRE_MARKET_SCAN";
    public static final String OI_SCAN = "OI_SCAN";
    public static final String MARKET_OPEN = "MARKET_OPEN";
    public static final String INTRADAY_ENTRY_CUTOFF = "INTRADAY_ENTRY_CUTOFF";
    public static final String INTRADAY_SQUARE_OFF = "INTRADAY_SQUARE_OFF";
    public static final String MARKET_CLOSE = "MARKET_CLOSE";

    /**
     * Creates a new {@link ScheduledEvent} with the given event type and the current timestamp.
     *
     * @param eventType the type of scheduled event (e.g., {@link #PRE_MARKET_SCAN})
     * @return a new {@link ScheduledEvent} instance
     */
    public static ScheduledEvent of(String eventType) {
        return new ScheduledEvent(eventType, Instant.now());
    }

    /**
     * Creates a new {@link ScheduledEvent} with the given event type and timestamp.
     *
     * @param eventType the type of scheduled event (e.g., {@link #MARKET_CLOSE})
     * @param timestamp the instant at which this event occurred or should occur
     * @return a new {@link ScheduledEvent} instance
     */
    public static ScheduledEvent of(String eventType, Instant timestamp) {
        return new ScheduledEvent(eventType, timestamp);
    }
}
