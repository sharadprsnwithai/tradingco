package com.tradingbot.strategy;

import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.CircularCandleBuffer;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Candle;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> strategyExecutors = new ConcurrentHashMap<>();
    private volatile Map<String, List<Strategy>> symbolToStrategies = Map.of();

    // Reactive multicast sink for all generated trading signals
    private final Sinks.Many<Signal> signalSink = Sinks.many().multicast().directBestEffort();

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
                signalSink.tryEmitNext(signal);
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
