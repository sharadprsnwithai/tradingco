package com.tradingbot.strategy;

import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.CircularCandleBuffer;
import com.tradingbot.marketdata.KiteHistoricalDataService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance, decoupled In-Memory Strategy Engine.
 * Features Java 21 Virtual-Thread isolated execution per strategy,
 * automatic symbol subscription via MarketDataHub, and a non-blocking Signal stream.
 */
@Service
public class StrategyEngine {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngine.class);

    private final MarketDataHub marketDataHub;
    private final CandleAggregator candleAggregator;

    // Historical data backfill services (optional — only present when the corresponding
    // broker modules are on the classpath). Injected as fields (not constructor) so the
    // existing 3-arg test constructor keeps working; warmup no-ops when these are null.
    @Autowired(required = false)
    private KiteHistoricalDataService kiteHistoricalDataService;
    @Autowired(required = false)
    private ShoonyaHistoricalDataService shoonyaHistoricalDataService;
    @Autowired(required = false)
    private InstrumentMasterService instrumentMaster;

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    // Timeframes seeded at startup so indicators (SuperTrend/RSI/VWAP) have enough history
    // immediately after a mid-day (or cold) restart instead of waiting hours for live bars.
    // "5" covers the VWAP strategy, "15"/"60" cover SuperTrend/RSI strategies.
    private static final List<String> WARMUP_TIMEFRAMES = List.of("5", "15", "60");
    private static final int WARMUP_NUM_CANDLES = 100;
    private static final int WARMUP_MIN_CANDLES = 100;

    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> strategyExecutors = new ConcurrentHashMap<>();
    private volatile Map<String, List<Strategy>> symbolToStrategies = Map.of();

    // Reactive multicast sink for all generated trading signals.
    // NOTE: use onBackpressureBuffer (NOT directBestEffort). directBestEffort drops a signal
    // for any subscriber lacking demand at that instant; the OMS pipeline uses concatMap
    // (serial/slow) and would silently miss live signals/orders under backpressure. Buffering
    // guarantees every subscriber (Telegram alerts + OMS execution) receives all signals.
    private final Sinks.Many<Signal> signalSink = Sinks.many().multicast().onBackpressureBuffer();

    private final List<Disposable> subscriptions = new ArrayList<>();

    /**
     * Constructs a new {@link StrategyEngine} with the given dependencies.
     * Registers any strategies provided by Spring autowiring.
     *
     * @param marketDataHub     the market data hub providing tick and candle streams
     * @param candleAggregator  the candle aggregator for accessing buffered candle data
     * @param initialStrategies optional list of strategies to register at startup (may be {@code null})
     */
    public StrategyEngine(
        MarketDataHub marketDataHub,
        CandleAggregator candleAggregator,
        @Autowired(required = false) List<Strategy> initialStrategies
    ) {
        this.marketDataHub = marketDataHub;
        this.candleAggregator = candleAggregator;

        if (initialStrategies != null) {
            for (Strategy s : initialStrategies) {
                registerStrategy(s);
            }
        }
    }

    /**
     * Initializes the strategy engine by subscribing to tick and candle streams
     * from the {@link MarketDataHub} and synchronizing symbol subscriptions.
     */
    @PostConstruct
    public void start() {
        // Subscribe to tick stream from MarketDataHub
        Disposable tickSub = marketDataHub.getTickStream()
            .publishOn(Schedulers.boundedElastic())
            .subscribe(this::routeTick, err -> log.error("Error in StrategyEngine tick stream: {}", err.getMessage()));
        subscriptions.add(tickSub);

        // Subscribe to closed candle stream from MarketDataHub
        Disposable candleSub = marketDataHub.getCandleStream(null)
            .publishOn(Schedulers.boundedElastic())
            .subscribe(this::routeCandle, err -> log.error("Error in StrategyEngine candle stream: {}", err.getMessage()));
        subscriptions.add(candleSub);

        syncSubscriptions();

        // If the market is already open (e.g. a mid-day container restart), run PRE_MARKET_SCAN
        // immediately so every strategy resolves its underlying / resets daily state now,
        // instead of waiting until the next 08:30 scheduled event. resolveUnderlying only
        // touches the local instrument master, so it is safe to run before broker auth.
        if (isMarketOpenNow()) {
            log.info("Market already open at startup — dispatching PRE_MARKET_SCAN to all strategies");
            dispatchSchedule(ScheduledEvent.of(ScheduledEvent.PRE_MARKET_SCAN));
        }

        log.info("StrategyEngine started with {} active strategies", strategies.size());
    }

    /**
     * Register a new Strategy instance dynamically (Pluggable SPI).
     */
    public synchronized void registerStrategy(Strategy strategy) {
        if (strategy == null || strategy.getStrategyId() == null) {
            return;
        }

        String strategyId = strategy.getStrategyId();
        if (strategies.containsKey(strategyId)) {
            log.warn("Strategy {} already registered. Overwriting.", strategyId);
            unregisterStrategy(strategyId);
        }

        // Dedicated Virtual Thread executor per strategy for complete isolation
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        strategyExecutors.put(strategyId, executor);
        strategies.put(strategyId, strategy);

        // Initialize strategy with decoupled context
        StrategyContext context = new StrategyContextImpl(strategyId, strategy.getAssignedAccountId());
        strategy.init(context);

        // Index strategy by subscribed symbols
        updateSymbolIndex();

        log.info("Registered strategy '{}' (account: '{}') listening to symbols: {}",
            strategyId, strategy.getAssignedAccountId(), strategy.getSubscribedSymbols());
    }

    /**
     * Unregister and destroy a Strategy instance cleanly.
     */
    public synchronized void unregisterStrategy(String strategyId) {
        Strategy strategy = strategies.remove(strategyId);
        if (strategy != null) {
            try {
                strategy.destroy();
            } catch (Exception e) {
                log.warn("Error destroying strategy {}: {}", strategyId, e.getMessage());
            }
        }

        ExecutorService executor = strategyExecutors.remove(strategyId);
        if (executor != null) {
            executor.shutdown();
        }

        updateSymbolIndex();
        log.info("Unregistered strategy '{}'", strategyId);
    }

    /**
     * Synchronize MarketDataHub subscriptions with the union of all active strategy symbols.
     */
    public synchronized void syncSubscriptions() {
        updateSymbolIndex();

        List<String> allSymbols = strategies.values().stream()
            .filter(Strategy::isEnabled)
            .flatMap(s -> s.getSubscribedSymbols().stream())
            .distinct()
            .toList();

        if (!allSymbols.isEmpty()) {
            marketDataHub.subscribe(allSymbols)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
            log.info("Synchronized MarketDataHub subscriptions for {} symbols: {}", allSymbols.size(), allSymbols);
        }
    }

    /**
     * Dispatches scheduled clock event to all registered enabled strategies in parallel virtual threads.
     */
    public void dispatchSchedule(ScheduledEvent event) {
        for (Strategy strategy : strategies.values()) {
            if (strategy.isEnabled()) {
                ExecutorService exec = strategyExecutors.get(strategy.getStrategyId());
                if (exec != null && !exec.isShutdown()) {
                    exec.submit(() -> {
                        try {
                            strategy.onSchedule(event);
                        } catch (Exception e) {
                            log.error("Error executing onSchedule for strategy {}: {}", strategy.getStrategyId(), e.getMessage(), e);
                        }
                    });
                }
            }
        }
    }

    /**
     * Backfills historical candles into the {@link CandleAggregator} for every subscribed
     * symbol/timeframe of all strategies, so indicator calculations (SuperTrend, RSI, VWAP)
     * have sufficient look-back immediately after a (mid-day) restart. Without this, the
     * aggregator only holds live bars received since process start and strategies stay blind
     * for hours. Mirrors the two-tier fetch used by {@code MarketClockScheduler}: Kite first,
     * Shoonya as fallback. No-op when the history services are unavailable or buffers are
     * already populated.
     * <p>
     * Must be called after broker authentication (the Kite/Shoonya historical APIs require a
     * valid session), e.g. from the startup {@code ApplicationRunner}.
     */
    public void warmupAllStrategies() {
        if (kiteHistoricalDataService == null && shoonyaHistoricalDataService == null) {
            log.info("Warmup skipped — no historical data service available");
            return;
        }

        List<KiteHistoricalDataService.KiteWarmupRequest> requests = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Strategy s : strategies.values()) {
            for (String sym : s.getSubscribedSymbols()) {
                for (String tf : WARMUP_TIMEFRAMES) {
                    String key = sym + "|" + tf;
                    if (!seen.add(key)) continue;
                    Optional<CircularCandleBuffer> buf = candleAggregator.getBuffer(sym, tf);
                    if (buf.isPresent() && buf.get().size() >= WARMUP_MIN_CANDLES) continue;
                    requests.add(new KiteHistoricalDataService.KiteWarmupRequest(sym, tf, WARMUP_NUM_CANDLES));
                }
            }
        }

        if (requests.isEmpty()) {
            log.info("Warmup skipped — all strategy symbols already have sufficient candle history");
            return;
        }

        if (kiteHistoricalDataService == null) {
            // Kite module unavailable — backfill solely from Shoonya.
            Flux.fromIterable(requests)
                .flatMap(req -> fallbackShoonyaWarmup(req.symbol(), req.timeframe(), req.numCandles()))
                .blockLast(Duration.ofSeconds(120));
            log.info("Historical candle warmup complete (Shoonya fallback)");
            return;
        }

        log.info("Warming up historical candles for {} symbol/timeframe requests", requests.size());
        kiteHistoricalDataService.warmupSequentially(requests)
            .flatMap(res -> {
                if (!res.candles().isEmpty()) {
                    seedCandlesCleared(res.symbol(), res.timeframe(), res.candles());
                    return Mono.empty();
                }
                // Kite returned nothing (disabled / not authed / no data) -> try Shoonya fallback
                return fallbackShoonyaWarmup(res.symbol(), res.timeframe(), WARMUP_NUM_CANDLES);
            })
            .blockLast(Duration.ofSeconds(120));
        log.info("Historical candle warmup complete");
    }

    /**
     * Replaces the aggregator's existing candles for a symbol/timeframe with the supplied
     * historical series. Clearing first guarantees chronological order: live bars that may
     * already be in the buffer would otherwise sit ahead of the (older) seeded history and
     * corrupt SuperTrend/RSI calculations. Live aggregation continues to append new bars after.
     */
    private void seedCandlesCleared(String symbol, String timeframe, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;
        candleAggregator.getOrCreateBuffer(symbol, timeframe).clear();
        candleAggregator.seedCandles(symbol, timeframe, candles);
        log.info("Seeded {} historical {} candles for {}", candles.size(), timeframe, symbol);
    }

    private Mono<Void> fallbackShoonyaWarmup(String symbol, String timeframe, int numCandles) {
        if (shoonyaHistoricalDataService == null || instrumentMaster == null) return Mono.empty();
        return instrumentMaster.findByCanonicalSymbol(symbol)
            .map(Instrument::shoonyaToken)
            .flatMap(token -> {
                if (token == null || token.isBlank()) return Mono.empty();
                String exchange = symbol.startsWith("NSE:") ? "NSE" : "NFO";
                return shoonyaHistoricalDataService.fetchHistoricalCandles(symbol, exchange, token, timeframe, numCandles)
                    .doOnNext(candles -> seedCandlesCleared(symbol, timeframe, candles));
            })
            .then();
    }

    /**
     * Returns true when the current IST time falls within the continuous market session
     * (Mon–Fri, 09:15–15:30). Used to decide whether to run a PRE_MARKET_SCAN at startup.
     */
    private boolean isMarketOpenNow() {
        ZonedDateTime now = ZonedDateTime.now(IST_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    /**
     * Route incoming tick to subscribing strategies via virtual threads.
     */
    private void routeTick(Tick tick) {
        if (tick == null || tick.symbol() == null) return;
        List<Strategy> targets = symbolToStrategies.get(tick.symbol());
        if (targets != null && !targets.isEmpty()) {
            for (Strategy strategy : targets) {
                if (strategy.isEnabled()) {
                    ExecutorService exec = strategyExecutors.get(strategy.getStrategyId());
                    if (exec != null && !exec.isShutdown()) {
                        exec.submit(() -> {
                            try {
                                strategy.onTick(tick);
                            } catch (Exception e) {
                                log.error("Error in onTick for strategy {}: {}", strategy.getStrategyId(), e.getMessage(), e);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * Route closed candle to subscribing strategies via virtual threads.
     */
    private void routeCandle(Candle candle) {
        if (candle == null || candle.symbol() == null) return;
        List<Strategy> targets = symbolToStrategies.get(candle.symbol());
        if (targets != null && !targets.isEmpty()) {
            for (Strategy strategy : targets) {
                if (strategy.isEnabled()) {
                    ExecutorService exec = strategyExecutors.get(strategy.getStrategyId());
                    if (exec != null && !exec.isShutdown()) {
                        exec.submit(() -> {
                            try {
                                strategy.onCandle(candle);
                            } catch (Exception e) {
                                log.error("Error in onCandle for strategy {}: {}", strategy.getStrategyId(), e.getMessage(), e);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * Rebuilds the symbol-to-strategies index by iterating all registered strategies
     * and mapping each subscribed symbol to its subscribing strategies.
     */
    private synchronized void updateSymbolIndex() {
        Map<String, List<Strategy>> newIndex = new HashMap<>();
        for (Strategy strategy : strategies.values()) {
            for (String symbol : strategy.getSubscribedSymbols()) {
                newIndex.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>()).add(strategy);
            }
        }
        this.symbolToStrategies = Map.copyOf(newIndex);
    }

    /**
     * Pauses a registered strategy by disabling it.
     * The strategy remains registered but will not receive tick, candle, or schedule events.
     *
     * @param strategyId the unique identifier of the strategy to pause
     */
    public void pauseStrategy(String strategyId) {
        Strategy s = strategies.get(strategyId);
        if (s != null) {
            s.setEnabled(false);
            log.info("Strategy '{}' PAUSED", strategyId);
        }
    }

    /**
     * Resumes a paused strategy by enabling it.
     * The strategy will once again receive tick, candle, and schedule events.
     *
     * @param strategyId the unique identifier of the strategy to resume
     */
    public void resumeStrategy(String strategyId) {
        Strategy s = strategies.get(strategyId);
        if (s != null) {
            s.setEnabled(true);
            log.info("Strategy '{}' RESUMED", strategyId);
        }
    }

    /**
     * Returns a reactive stream of all trading signals emitted by any registered strategy.
     *
     * @return a {@link Flux} of {@link Signal} from all strategies
     */
    public Flux<Signal> getSignalStream() {
        return signalSink.asFlux();
    }

    /**
     * Returns a reactive stream of trading signals filtered to a specific strategy.
     *
     * @param strategyId the unique identifier of the strategy to filter by
     * @return a {@link Flux} of {@link Signal} from the specified strategy only
     */
    public Flux<Signal> getSignalStream(String strategyId) {
        return signalSink.asFlux().filter(sig -> strategyId.equalsIgnoreCase(sig.strategyId()));
    }

    /**
     * Returns an unmodifiable list of all currently registered strategies.
     *
     * @return an unmodifiable {@link List} of registered {@link Strategy} instances
     */
    public List<Strategy> getRegisteredStrategies() {
        return Collections.unmodifiableList(new ArrayList<>(strategies.values()));
    }

    /**
     * Retrieves a registered strategy by its unique identifier.
     *
     * @param strategyId the unique identifier of the strategy
     * @return an {@link Optional} containing the strategy if found, or empty if not registered
     */
    public Optional<Strategy> getStrategy(String strategyId) {
        return Optional.ofNullable(strategies.get(strategyId));
    }

    /**
     * Gracefully shuts down the strategy engine: disposes reactive subscriptions,
     * shuts down virtual-thread executors, and destroys all registered strategies.
     */
    @PreDestroy
    public void cleanup() {
        for (Disposable d : subscriptions) {
            if (!d.isDisposed()) d.dispose();
        }
        for (ExecutorService exec : strategyExecutors.values()) {
            exec.shutdown();
        }
        for (Strategy s : strategies.values()) {
            try {
                s.destroy();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Inner StrategyContext implementation connected to StrategyEngine & CandleAggregator.
     */
    private class StrategyContextImpl implements StrategyContext {
        private final String strategyId;
        private final String accountId;

        /**
         * Constructs a new {@link StrategyContextImpl} bound to the given strategy and account.
         *
         * @param strategyId the unique identifier of the owning strategy
         * @param accountId  the trading account assigned to this strategy
         */
        StrategyContextImpl(String strategyId, String accountId) {
            this.strategyId = strategyId;
            this.accountId = accountId;
        }

        @Override
        public String getStrategyId() {
            return strategyId;
        }

        @Override
        public String getAssignedAccountId() {
            return accountId;
        }

        @Override
        public void emitSignal(Signal signal) {
            if (signal != null) {
                log.info("SIGNAL EMITTED [{}] {} {} x {} @ {} (tag: {})",
                    strategyId, signal.signalType(), signal.symbol(), signal.quantity(), signal.price(), signal.tag());
                signalSink.emitNext(signal, (signalType, emitResult) -> {
                    if (emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                        return true;
                    }
                    return false;
                });
            }
        }

        @Override
        public void requestSubscriptionSync() {
            log.info("Strategy '{}' requested subscription sync", strategyId);
            StrategyEngine.this.syncSubscriptions();
        }

        @Override
        public Optional<Candle> getLastCandle(String symbol, String timeframe) {
            return candleAggregator.getBuffer(symbol, timeframe)
                .flatMap(CircularCandleBuffer::getLast);
        }

        @Override
        public List<Candle> getHistoricalCandles(String symbol, String timeframe, int count) {
            return candleAggregator.getBuffer(symbol, timeframe)
                .map(buf -> buf.getLast(count))
                .orElseGet(Collections::emptyList);
        }

        @Override
        public double[] getClosePrices(String symbol, String timeframe) {
            return candleAggregator.getBuffer(symbol, timeframe)
                .map(CircularCandleBuffer::getClosePrices)
                .orElseGet(() -> new double[0]);
        }

        @Override
        public Instant now() {
            return Instant.now();
        }
    }
}
