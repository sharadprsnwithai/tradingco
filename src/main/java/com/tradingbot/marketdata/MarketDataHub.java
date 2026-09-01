package com.tradingbot.marketdata;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.adapter.kite.KiteBrokerAdapter;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Tick;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Market Data Hub coordinating market feed ingestion, silence detection,
 * hot-warm cross-broker failover (Kite -> Shoonya), and aggregation pipeline.
 */
@Service
public class MarketDataHub {

    private static final Logger log = LoggerFactory.getLogger(MarketDataHub.class);
    private static final String PRIMARY_BROKER = "ZERODHA";
    private static final String SECONDARY_BROKER = "SHOONYA";

    private final BrokerAdapterRegistry brokerRegistry;
    private final InstrumentMasterService instrumentMaster;
    private final CandleAggregator candleAggregator;
    private final Duration silenceThreshold;

    private final List<String> activeSymbols = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> activeBrokerId = new AtomicReference<>(PRIMARY_BROKER);
    private final AtomicBoolean failedOver = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastTickTime = new AtomicReference<>(Instant.now());

    private final AtomicReference<Disposable> feedSubscription = new AtomicReference<>();
    private final AtomicReference<Disposable> watchdogSubscription = new AtomicReference<>();
    private final AtomicBoolean failoverRefusedLogged = new AtomicBoolean(false);
    private final AtomicBoolean silenceWarned = new AtomicBoolean(false);
    private final AtomicBoolean primaryOnly = new AtomicBoolean(false);
    /** Timestamp (millis) of the last forced primary-reconnect attempt while parked on a silent primary. */
    private final java.util.concurrent.atomic.AtomicLong lastPrimaryRecoveryAttempt = new java.util.concurrent.atomic.AtomicLong(0);
    /** Min interval between forced primary reconnect attempts while the feed is silent. */
    private static final long PRIMARY_RECOVERY_RETRY_INTERVAL_MS = 60_000;

    /**
     * Constructs a MarketDataHub with the given dependencies.
     *
     * @param brokerRegistry registry for obtaining broker adapters
     * @param instrumentMaster service for instrument lookups and caching
     * @param candleAggregator aggregator for converting ticks into candles
     * @param silenceThresholdSeconds number of seconds without ticks before triggering failover
     */
    public MarketDataHub(
        BrokerAdapterRegistry brokerRegistry,
        InstrumentMasterService instrumentMaster,
        CandleAggregator candleAggregator,
        @Value("${bot.marketdata.silence-threshold-seconds:3}") int silenceThresholdSeconds
    ) {
        this.brokerRegistry = brokerRegistry;
        this.instrumentMaster = instrumentMaster;
        this.candleAggregator = candleAggregator;
        this.silenceThreshold = Duration.ofSeconds(silenceThresholdSeconds);
    }

    /**
     * Start subscribing to market data for the given canonical symbols.
     */
    public synchronized Mono<Void> subscribe(List<String> canonicalSymbols) {
        if (canonicalSymbols == null || canonicalSymbols.isEmpty()) {
            return Mono.empty();
        }

        this.activeSymbols.clear();
        this.activeSymbols.addAll(canonicalSymbols);

        // Preload active symbols into instrument master cache
        return Flux.fromIterable(canonicalSymbols)
            .flatMap(instrumentMaster::findByCanonicalSymbol)
            .doOnNext(instrumentMaster::cacheActive)
            .then(Mono.defer(() -> connectFeed(activeBrokerId.get())))
            .doOnSuccess(v -> startWatchdog());
    }

    /**
     * Connect to the market data feed of the specified broker.
     */
    public synchronized Mono<Void> connectFeed(String brokerId) {
        Disposable oldSub = feedSubscription.getAndSet(null);
        if (oldSub != null && !oldSub.isDisposed()) {
            oldSub.dispose();
        }

        return brokerRegistry.getByBrokerId(brokerId)
            .switchIfEmpty(Mono.error(new IllegalStateException("Broker adapter not found for ID: " + brokerId)))
            .doOnNext(adapter -> {
                log.info("Connecting MarketDataHub feed to broker: {} with {} symbols", brokerId, activeSymbols.size());
                this.activeBrokerId.set(brokerId);
                this.lastTickTime.set(Instant.now());

                Disposable newSub = adapter.subscribeMarketData(new ArrayList<>(activeSymbols))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(tick -> {
                        lastTickTime.set(Instant.now());
                        failoverRefusedLogged.set(false); // feed healthy again - re-arm refusal logging
                        silenceWarned.set(false);
                        // Feed is delivering ticks again — re-arm the full watchdog. Without
                        // this, primaryOnly (set after a failed failover cycle) permanently
                        // disables silence detection even after the feed recovers.
                        primaryOnly.set(false);
                        candleAggregator.onTick(tick);
                    })
                    .doOnError(err -> {
                        if (SECONDARY_BROKER.equals(brokerId)) {
                            log.error("Error on {} feed stream: {}. Reverting to primary (failover abandoned).",
                                brokerId, err.getMessage());
                            failedOver.set(false);
                            primaryOnly.set(true);
                            connectFeed(PRIMARY_BROKER).subscribe();
                        } else {
                            log.error("Error on {} feed stream: {}. Triggering failover.", brokerId, err.getMessage());
                            triggerFailover("Feed stream error: " + err.getMessage());
                        }
                    })
                    .subscribe(
                        v -> {},
                        err -> log.error("{} feed stream terminated: {}", brokerId, err.getMessage())
                    );

                feedSubscription.set(newSub);
            })
            .then();
    }

    /**
     * Start background silence detector to monitor feed health.
     */
    private synchronized void startWatchdog() {
        if (watchdogSubscription.get() != null && !watchdogSubscription.get().isDisposed()) {
            return;
        }

        Disposable watchdog = Flux.interval(Duration.ofSeconds(1))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(tick -> checkSilence());

        watchdogSubscription.set(watchdog);
    }

    /**
     * Checks if current active feed has gone silent beyond the threshold.
     * Only enforced during market hours (09:15–15:31 IST, Mon–Fri) — no ticks are
     * expected when the exchange is closed, so silence then is not an alarm.
     */
    public void checkSilence() {
        if (activeSymbols.isEmpty()) {
            return;
        }
        if (!isMarketHours()) {
            return;
        }

        Instant last = lastTickTime.get();
        if (last == null || Duration.between(last, Instant.now()).compareTo(silenceThreshold) <= 0) {
            return;
        }

        if (failedOver.get()) {
            // Secondary has been silent since failover -> revert to primary (one-shot) so the
            // hub is never parked on a dead feed. Primary's own SDK auto-reconnect is the recovery path.
            log.error("Secondary feed {} silent (>{}s) after failover - reverting to primary {}",
                activeBrokerId.get(), silenceThreshold.toSeconds(), PRIMARY_BROKER);
            failedOver.set(false);
            primaryOnly.set(true);
            connectFeed(PRIMARY_BROKER).subscribe();
            return;
        }

        if (primaryOnly.get()) {
            // Parked on the primary after a failed failover cycle. If the primary also stays
            // silent, its SDK auto-reconnect may be wedged on a zombie WebSocket — force a
            // full reconnect periodically so the adapter tears down and rebuilds the ticker
            // (ensureTickerConnected detects zombie connections). Without this loop the hub
            // sits on a dead feed for the rest of the day with the watchdog disarmed.
            long now = System.currentTimeMillis();
            if (now - lastPrimaryRecoveryAttempt.get() > PRIMARY_RECOVERY_RETRY_INTERVAL_MS) {
                lastPrimaryRecoveryAttempt.set(now);
                log.error("Primary feed {} silent (>{}s without ticks) - forcing reconnect attempt",
                    activeBrokerId.get(), silenceThreshold.toSeconds());
                lastTickTime.set(Instant.now()); // reset so the next check waits a full cycle
                connectFeed(PRIMARY_BROKER).subscribe();
            }
            return;
        }

        String msg = String.format("Feed silence detected on %s (> %ds without ticks). Triggering failover to %s.",
            activeBrokerId.get(), silenceThreshold.toSeconds(), SECONDARY_BROKER);
        if (silenceWarned.compareAndSet(false, true)) {
            log.warn(msg); // once per silence episode; reset on next tick
        }
        triggerFailover(msg);
    }

    /**
     * True between 09:15 and 15:31 IST on weekdays.
     */
    private static boolean isMarketHours() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (now.getDayOfWeek().getValue() >= 6) return false;
        int mins = now.getHour() * 60 + now.getMinute();
        return mins >= 555 && mins <= 931;
    }

    /**
     * Trigger sticky failover to secondary broker (Shoonya).
     * If the secondary broker is disabled, failover is refused — switching to a
     * disabled adapter would mean no data at all; the primary feed's own SDK
     * reconnect loop is the safer recovery path. The refusal is logged once and
     * the watchdog stays armed (the latch is NOT set on refusal).
     */
    public synchronized void triggerFailover(String reason) {
        if (failedOver.get() || primaryOnly.get()) return;

        // Synchronous registry lookup — this method is called from the watchdog's
        // interval thread where blocking a Mono is not permitted
        boolean secondaryEnabled = brokerRegistry.findByBrokerId(SECONDARY_BROKER)
            .map(BrokerAdapter::isEnabled)
            .orElse(false);
        if (!secondaryEnabled) {
            if (failoverRefusedLogged.compareAndSet(false, true)) {
                log.error("Feed failure on {} ({}), but failover target {} is DISABLED - staying on primary (SDK auto-reconnect active)",
                    activeBrokerId.get(), reason, SECONDARY_BROKER);
            }
            return;
        }

        if (failedOver.compareAndSet(false, true)) {
            log.warn("FAILOVER ACTIVATED: Switching MarketDataHub from {} to {} due to: {}",
                activeBrokerId.get(), SECONDARY_BROKER, reason);
            connectFeed(SECONDARY_BROKER).subscribe();
        }
    }

    /**
     * Manually switch active broker.
     */
    public synchronized Mono<Void> switchBroker(String newBrokerId, String reason) {
        return Mono.defer(() -> {
            log.info("Manual broker switch requested to {} (reason: {})", newBrokerId, reason);
            if (!PRIMARY_BROKER.equalsIgnoreCase(newBrokerId)) {
                failedOver.set(true);
            }
            return connectFeed(newBrokerId);
        });
    }

    /**
     * Returns a reactive stream of all ticks from the active feed.
     *
     * @return Flux emitting Tick events for all subscribed symbols
     */
    public Flux<Tick> getTickStream() {
        return candleAggregator.getTickStream();
    }

    /**
     * Returns a reactive stream of ticks filtered to the specified symbol.
     *
     * @param symbol canonical symbol to filter (e.g. "NSE:RELIANCE")
     * @return Flux emitting Tick events for the given symbol
     */
    public Flux<Tick> getTickStream(String symbol) {
        return candleAggregator.getTickStream(symbol);
    }

    /**
     * Returns a reactive stream of aggregated candles, optionally filtered by timeframe.
     *
     * @param timeframe timeframe to filter by (e.g. "1", "5", "15"), or null for all timeframes
     * @return Flux emitting Candle events matching the filter
     */
    public Flux<Candle> getCandleStream(String timeframe) {
        return candleAggregator.getCandleStream()
            .filter(c -> timeframe == null || timeframe.equalsIgnoreCase(c.timeframe()));
    }

    /**
     * Returns a reactive stream of aggregated candles for a specific symbol and timeframe.
     *
     * @param symbol canonical symbol to filter (e.g. "NSE:RELIANCE")
     * @param timeframe timeframe to filter by (e.g. "1", "5", "15")
     * @return Flux emitting Candle events for the given symbol and timeframe
     */
    public Flux<Candle> getCandleStream(String symbol, String timeframe) {
        return candleAggregator.getCandleStream(symbol, timeframe);
    }

    /**
     * Returns the underlying candle aggregator instance.
     *
     * @return CandleAggregator used for tick-to-candle conversion
     */
    public CandleAggregator getCandleAggregator() {
        return candleAggregator;
    }

    /**
     * Returns the ID of the currently active broker.
     *
     * @return broker ID string (e.g. "ZERODHA", "SHOONYA")
     */
    public String getActiveBroker() {
        return activeBrokerId.get();
    }

    /**
     * Checks whether a failover to the secondary broker has been triggered.
     *
     * @return true if the hub has failed over from the primary broker
     */
    public boolean isFailedOver() {
        return failedOver.get();
    }

    /**
     * Forces a clean WebSocket reconnect on the active broker adapter after instrument
     * sync. Resolves fresh tokens for all active symbols, purges stale tokens, and
     * reconnects the live feed. Called from the scheduler after the 08:30 pre-market
     * instrument master sync to prevent the feed from being stuck on expired contract
     * tokens (e.g. previous-month FUT contract that was subscribed before the sync
     * updated the database with the new contract's token).
     */
    public void reconnectAfterInstrumentSync() {
        if (activeSymbols.isEmpty()) {
            log.info("No active symbols — skipping post-instrument-sync reconnect");
            return;
        }
        String brokerId = activeBrokerId.get();
        brokerRegistry.getByBrokerId(brokerId)
            .subscribe(adapter -> {
                if (adapter instanceof KiteBrokerAdapter kite) {
                    log.info("Post-instrument-sync: forcing Kite WebSocket reconnect for {} symbols", activeSymbols.size());
                    kite.forceReconnectAfterInstrumentSync(new ArrayList<>(activeSymbols));
                    lastTickTime.set(Instant.now()); // reset silence timer
                } else {
                    log.info("Post-instrument-sync: adapter {} does not require explicit reconnect", brokerId);
                }
            }, err -> log.error("Post-instrument-sync reconnect failed: {}", err.getMessage()));
    }

    /**
     * Returns the list of canonical symbols currently being tracked.
     *
     * @return list of canonical symbol strings being tracked
     */
    public List<String> getActiveSymbols() {
        return Collections.unmodifiableList(activeSymbols);
    }

    /**
     * Disposes active feed and watchdog subscriptions on application shutdown.
     */
    @PreDestroy
    public void cleanup() {
        Disposable feed = feedSubscription.getAndSet(null);
        if (feed != null && !feed.isDisposed()) {
            feed.dispose();
        }
        Disposable watchdog = watchdogSubscription.getAndSet(null);
        if (watchdog != null && !watchdog.isDisposed()) {
            watchdog.dispose();
        }
    }
}
