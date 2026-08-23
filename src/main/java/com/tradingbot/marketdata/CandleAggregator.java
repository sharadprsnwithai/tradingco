package com.tradingbot.marketdata;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time Synthetic Multi-Timeframe Candle Aggregator.
 * Converts incoming ticks into clock-aligned 1m base candles, and derives 3m, 5m, and 15m bars.
 * Enforces flat-bar gap filling and publishes dual reactive streams (Ticks + Closed Candles).
 */
@Service
public class CandleAggregator {

    private static final Logger log = LoggerFactory.getLogger(CandleAggregator.class);
    private static final List<Integer> HIGHER_TIMEFRAMES = List.of(3, 5, 15, 60);

    // symbol -> (timeframe -> CircularCandleBuffer)
    private final Map<String, Map<String, CircularCandleBuffer>> buffers = new ConcurrentHashMap<>();

    // symbol -> forming 1m candle
    private final Map<String, FormingCandle> forming1mCandles = new ConcurrentHashMap<>();

    // symbol -> (timeframe -> forming higher candle)
    private final Map<String, Map<Integer, FormingCandle>> formingHigherCandles = new ConcurrentHashMap<>();

    // Multicast reactive sinks
    private final Sinks.Many<Candle> candleSink = Sinks.many().multicast().directBestEffort();
    private final Sinks.Many<Tick> tickSink = Sinks.many().multicast().directBestEffort();

    /**
     * Process an incoming tick: updates forming candles and emits raw tick stream.
     */
    public void onTick(Tick tick) {
        if (tick == null || tick.symbol() == null || tick.ltp() == null) {
            return;
        }

        // 1. Emit to real-time tick stream for instantaneous SL / breakout execution
        tickSink.tryEmitNext(tick);

        String symbol = tick.symbol();
        Instant tickTime = tick.timestamp() != null ? tick.timestamp() : Instant.now();
        long tickMinute = tickTime.getEpochSecond() / 60;

        synchronized (getSymbolLock(symbol)) {
            FormingCandle current1m = forming1mCandles.get(symbol);

            if (current1m == null) {
                // Initialize first 1m bar
                forming1mCandles.put(symbol, new FormingCandle(
                    symbol,
                    "1",
                    tickMinute,
                    Instant.ofEpochSecond(tickMinute * 60),
                    tick.ltp(),
                    tick.ltp(),
                    tick.ltp(),
                    tick.ltp(),
                    tick.volume()
                ));
            } else if (current1m.minuteBucket == tickMinute) {
                // Intra-minute update
                current1m.high = current1m.high.max(tick.ltp());
                current1m.low = current1m.low.min(tick.ltp());
                current1m.close = tick.ltp();
                current1m.volume += tick.volume();
            } else if (current1m.minuteBucket < tickMinute) {
                // Minute rolled over -> close current 1m bar
                Candle closed1m = current1m.toClosedCandle();
                handleClosed1mCandle(closed1m);

                // Handle missing interval gap fill with flat bars
                long gapMinutes = tickMinute - current1m.minuteBucket;
                if (gapMinutes > 1) {
                    fillFlatCandles(symbol, current1m.close, current1m.minuteBucket + 1, tickMinute);
                }

                // Start new 1m forming candle
                forming1mCandles.put(symbol, new FormingCandle(
                    symbol,
                    "1",
                    tickMinute,
                    Instant.ofEpochSecond(tickMinute * 60),
                    tick.ltp(),
                    tick.ltp(),
                    tick.ltp(),
                    tick.ltp(),
                    tick.volume()
                ));
            }
        }
    }

    /**
     * Seeds historical candles into circular buffers (e.g. from Shoonya TPSeries warm-up).
     */
    public void seedCandles(String symbol, String timeframe, List<Candle> historicalCandles) {
        if (symbol == null || timeframe == null || historicalCandles == null) return;
        CircularCandleBuffer buffer = getOrCreateBuffer(symbol, timeframe);
        for (Candle c : historicalCandles) {
            buffer.add(c);
        }
        log.info("Seeded {} historical {} candles for {}", historicalCandles.size(), timeframe, symbol);
    }

    /**
     * Handles a completed 1-minute candle by storing it in the buffer,
     * emitting it to the candle stream, and aggregating into higher timeframes.
     *
     * @param candle1m the closed 1-minute candle
     */
    private void handleClosed1mCandle(Candle candle1m) {
        // 1. Store in 1m buffer
        CircularCandleBuffer buffer1m = getOrCreateBuffer(candle1m.symbol(), "1");
        buffer1m.add(candle1m);

        // 2. Emit closed 1m candle
        candleSink.tryEmitNext(candle1m);

        // 3. Aggregate into higher timeframes (3m, 5m, 15m)
        for (int tf : HIGHER_TIMEFRAMES) {
            aggregateHigherTimeframe(candle1m, tf);
        }
    }

    /**
     * Aggregates a 1-minute candle into a higher timeframe candle (3m, 5m, or 15m).
     * Closes the previous higher timeframe candle if a new bucket starts, and emits
     * the closed candle when the current bucket completes.
     *
     * @param candle1m        the 1-minute candle to aggregate
     * @param timeframeMinutes the target timeframe in minutes (e.g., 3, 5, 15)
     */
    private void aggregateHigherTimeframe(Candle candle1m, int timeframeMinutes) {
        String symbol = candle1m.symbol();
        long minuteEpoch = candle1m.timestamp().getEpochSecond() / 60;
        long bucketStartMinute = (minuteEpoch / timeframeMinutes) * timeframeMinutes;
        Instant bucketStartTime = Instant.ofEpochSecond(bucketStartMinute * 60);

        Map<Integer, FormingCandle> higherMap = formingHigherCandles.computeIfAbsent(symbol, s -> new ConcurrentHashMap<>());
        FormingCandle forming = higherMap.get(timeframeMinutes);

        if (forming == null || forming.minuteBucket != bucketStartMinute) {
            if (forming != null) {
                // Close previous higher timeframe candle
                Candle closedHigher = forming.toClosedCandle();
                getOrCreateBuffer(symbol, String.valueOf(timeframeMinutes)).add(closedHigher);
                candleSink.tryEmitNext(closedHigher);
            }
            // Start new higher timeframe forming candle
            forming = new FormingCandle(
                symbol,
                String.valueOf(timeframeMinutes),
                bucketStartMinute,
                bucketStartTime,
                candle1m.open(),
                candle1m.high(),
                candle1m.low(),
                candle1m.close(),
                candle1m.volume()
            );
            higherMap.put(timeframeMinutes, forming);
        } else {
            // Update existing higher timeframe candle
            forming.high = forming.high.max(candle1m.high());
            forming.low = forming.low.min(candle1m.low());
            forming.close = candle1m.close();
            forming.volume += candle1m.volume();
        }

        // Check if this 1m candle is the closing candle of the higher bucket (e.g. minute 4, 9, 14...)
        if ((minuteEpoch % timeframeMinutes) == (timeframeMinutes - 1)) {
            Candle closedHigher = forming.toClosedCandle();
            getOrCreateBuffer(symbol, String.valueOf(timeframeMinutes)).add(closedHigher);
            candleSink.tryEmitNext(closedHigher);
            higherMap.remove(timeframeMinutes);
        }
    }

    /**
     * Fills gaps in tick stream with zero-volume flat bars (O=H=L=C=prevClose).
     */
    private void fillFlatCandles(String symbol, BigDecimal prevClose, long fromMinute, long toMinute) {
        for (long m = fromMinute; m < toMinute; m++) {
            Instant t = Instant.ofEpochSecond(m * 60);
            Candle flatCandle = new Candle(symbol, "1", t, prevClose, prevClose, prevClose, prevClose, 0L);
            handleClosed1mCandle(flatCandle);
        }
    }

    /**
     * Returns the circular buffer for the given symbol and timeframe, creating it
     * with a default capacity of 300 if it does not exist.
     *
     * @param symbol    the trading symbol
     * @param timeframe the candle timeframe (e.g., "1", "5", "15")
     * @return the circular candle buffer for the symbol and timeframe
     */
    public CircularCandleBuffer getOrCreateBuffer(String symbol, String timeframe) {
        return buffers.computeIfAbsent(symbol, s -> new ConcurrentHashMap<>())
            .computeIfAbsent(timeframe, tf -> new CircularCandleBuffer(300));
    }

    /**
     * Returns the circular buffer for the given symbol and timeframe, or empty
     * if no buffer has been created for this pair.
     *
     * @param symbol    the trading symbol
     * @param timeframe the candle timeframe (e.g., "1", "5", "15")
     * @return an Optional containing the buffer if present, or empty otherwise
     */
    public Optional<CircularCandleBuffer> getBuffer(String symbol, String timeframe) {
        Map<String, CircularCandleBuffer> symBuffers = buffers.get(symbol);
        if (symBuffers == null) return Optional.empty();
        return Optional.ofNullable(symBuffers.get(timeframe));
    }

    /**
     * Returns a reactive stream of all closed candles across all symbols and timeframes.
     *
     * @return a Flux emitting every closed candle
     */
    public Flux<Candle> getCandleStream() {
        return candleSink.asFlux();
    }

    /**
     * Returns a reactive stream of closed candles filtered to a specific symbol.
     *
     * @param symbol the trading symbol to filter by
     * @return a Flux emitting closed candles for the given symbol
     */
    public Flux<Candle> getCandleStream(String symbol) {
        return candleSink.asFlux().filter(c -> c.symbol().equalsIgnoreCase(symbol));
    }

    /**
     * Returns a reactive stream of closed candles filtered to a specific symbol and timeframe.
     *
     * @param symbol    the trading symbol to filter by
     * @param timeframe the candle timeframe to filter by
     * @return a Flux emitting closed candles for the given symbol and timeframe
     */
    public Flux<Candle> getCandleStream(String symbol, String timeframe) {
        return candleSink.asFlux().filter(c -> c.symbol().equalsIgnoreCase(symbol) && c.timeframe().equalsIgnoreCase(timeframe));
    }

    /**
     * Returns a reactive stream of all incoming ticks across all symbols.
     *
     * @return a Flux emitting every raw tick
     */
    public Flux<Tick> getTickStream() {
        return tickSink.asFlux();
    }

    /**
     * Returns a reactive stream of incoming ticks filtered to a specific symbol.
     *
     * @param symbol the trading symbol to filter by
     * @return a Flux emitting raw ticks for the given symbol
     */
    public Flux<Tick> getTickStream(String symbol) {
        return tickSink.asFlux().filter(t -> t.symbol().equalsIgnoreCase(symbol));
    }

    /**
     * Returns a synchronized lock object for the given symbol, used to ensure
     * thread-safe access to per-symbol forming candle state.
     *
     * @param symbol the trading symbol
     * @return the interned symbol string used as a monitor lock
     */
    private Object getSymbolLock(String symbol) {
        return symbol.intern();
    }

    /**
     * Mutable holder for an in-progress (forming) candle that accumulates OHLCV
     * data before being closed and emitted as an immutable {@link Candle}.
     */
    private static class FormingCandle {
        final String symbol;
        final String timeframe;
        final long minuteBucket;
        final Instant startTime;
        BigDecimal open;
        BigDecimal high;
        BigDecimal low;
        BigDecimal close;
        long volume;

        /**
         * Constructs a new forming candle.
         *
         * @param symbol      the trading symbol
         * @param timeframe   the candle timeframe
         * @param minuteBucket the epoch minute bucket this candle belongs to
         * @param startTime   the start time of the candle
         * @param open        the opening price
         * @param high        the current high price
         * @param low         the current low price
         * @param close       the current closing price
         * @param volume      the cumulative volume
         */
        FormingCandle(String symbol, String timeframe, long minuteBucket, Instant startTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
            this.symbol = symbol;
            this.timeframe = timeframe;
            this.minuteBucket = minuteBucket;
            this.startTime = startTime;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        /**
         * Converts this forming candle into an immutable closed {@link Candle}.
         *
         * @return a new Candle with the current OHLCV values
         */
        Candle toClosedCandle() {
            return new Candle(symbol, timeframe, startTime, open, high, low, close, volume);
        }
    }
}
