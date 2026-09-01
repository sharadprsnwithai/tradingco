package com.tradingbot.scheduler;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.instrument.InstrumentSyncService;
import com.tradingbot.instrument.ShoonyaInstrumentSyncService;
import com.tradingbot.marketdata.KiteHistoricalDataService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.RiskManager;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.strategy.TechnicalIndicators;
import com.tradingbot.strategy.ironfly.IronFlyService;
import com.tradingbot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Automated Master Market Clock aligned to Indian Standard Time (Asia/Kolkata).
 * Automates pre-market auth (08:30), warm-up (09:05), market open (09:15),
 * entry cutoff (15:10), intraday auto-square-off (15:14), and market close (15:30).
 */
@Component
public class MarketClockScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketClockScheduler.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private final StrategyEngine strategyEngine;
    private final PositionManagerService positionManager;
    private final RiskManager riskManager;
    private final BrokerAdapterRegistry brokerRegistry;
    private final MarketDataHub marketDataHub;
    private final ShoonyaHistoricalDataService historicalDataService;
    private final KiteHistoricalDataService kiteHistoricalDataService;
    private final InstrumentMasterService instrumentMaster;
    private final InstrumentSyncService instrumentSyncService;
    private final ShoonyaInstrumentSyncService shoonyaInstrumentSyncService;
    private final TelegramBotService telegramBot;
    private final IronFlyService ironFlyService;

    // Injectable clock for testing — defaults to real IST clock
    private Supplier<LocalDate> clock = () -> LocalDate.now(IST_ZONE);

    // NSE trading holidays (default official 2026 calendar; configurable via bot.calendar.holidays)
    private final Set<LocalDate> nseHolidays = new HashSet<>();

    public MarketClockScheduler(
        StrategyEngine strategyEngine,
        PositionManagerService positionManager,
        RiskManager riskManager,
        BrokerAdapterRegistry brokerRegistry,
        MarketDataHub marketDataHub,
        ShoonyaHistoricalDataService historicalDataService,
        @Autowired(required = false) KiteHistoricalDataService kiteHistoricalDataService,
        InstrumentMasterService instrumentMaster,
        InstrumentSyncService instrumentSyncService,
        @Autowired(required = false) ShoonyaInstrumentSyncService shoonyaInstrumentSyncService,
        TelegramBotService telegramBot,
        @Autowired(required = false) IronFlyService ironFlyService,
        @Value("${bot.calendar.holidays:2026-01-15,2026-01-26,2026-03-03,2026-03-26,2026-03-31,2026-04-03,2026-04-14,2026-05-01,2026-05-28,2026-06-26,2026-09-14,2026-10-02,2026-10-20,2026-11-10,2026-11-24,2026-12-25}") String holidaysConfig
    ) {
        this.strategyEngine = strategyEngine;
        this.positionManager = positionManager;
        this.riskManager = riskManager;
        this.brokerRegistry = brokerRegistry;
        this.marketDataHub = marketDataHub;
        this.historicalDataService = historicalDataService;
        this.kiteHistoricalDataService = kiteHistoricalDataService;
        this.instrumentMaster = instrumentMaster;
        this.instrumentSyncService = instrumentSyncService;
        this.shoonyaInstrumentSyncService = shoonyaInstrumentSyncService;
        this.telegramBot = telegramBot;
        this.ironFlyService = ironFlyService;

        if (holidaysConfig != null && !holidaysConfig.isBlank()) {
            for (String h : holidaysConfig.split(",")) {
                String trimmed = h.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        nseHolidays.add(LocalDate.parse(trimmed));
                    } catch (Exception ex) {
                        log.warn("Failed to parse NSE holiday date: {}", trimmed);
                    }
                }
            }
        }
        log.info("Initialized Master Market Clock with {} NSE holidays from configuration", nseHolidays.size());
    }

    /**
     * Determines whether the given date is a trading day on NSE.
     * Returns {@code false} for weekends (Saturday/Sunday) and known exchange holidays.
     *
     * @param date the date to check, may be {@code null}
     * @return {@code true} if the date is a valid NSE trading day, {@code false} otherwise
     */
    public boolean isTradingDay(LocalDate date) {
        if (date == null) return false;
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !nseHolidays.contains(date);
    }

    /**
     * 08:30 AM IST: Pre-Market Authentication & Master Download.
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void onPreMarketAuth() {
        if (!isTradingDay(clock.get())) return;
        log.info("⏰ 08:30 IST: Triggering Pre-Market Broker Authentication & Master Sync");

        brokerRegistry.getAll()
            .filter(BrokerAdapter::isEnabled)
            .flatMap(adapter -> adapter.authenticate()
                .doOnSuccess(v -> log.info("Authenticated broker: {}", adapter.getBrokerId()))
                .onErrorResume(e -> {
                    log.error("Failed to authenticate {}: {}", adapter.getBrokerId(), e.getMessage());
                    return Mono.empty();
                }))
            .then(instrumentSyncService.syncFromKite())
            .doOnNext(count -> log.info("Instrument master synced: {} instruments", count))
            .onErrorResume(e -> {
                log.error("Instrument sync failed ({}), continuing with pre-market dispatch", e.getMessage());
                return reactor.core.publisher.Mono.empty();
            })
            .then(shoonyaInstrumentSyncService != null
                ? shoonyaInstrumentSyncService.syncFromShoonya()
                : reactor.core.publisher.Mono.empty())
            .doOnNext(c -> log.info("[SHOONYA-SYNC] Shoonya master token sync complete: {} tokens mapped", c))
            .onErrorResume(e -> {
                log.error("Shoonya master sync failed ({}), continuing", e.getMessage());
                return reactor.core.publisher.Mono.empty();
            })
            .doOnTerminate(() -> {
                strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.PRE_MARKET_SCAN));
                // Force clean Kite WebSocket reconnect with fresh instrument tokens BEFORE
                // syncSubscriptions re-registers strategy symbols. This purges stale tokens
                // (e.g. expired monthly FUT contracts) from the live feed subscription set.
                marketDataHub.reconnectAfterInstrumentSync();
                reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(5))
                    .subscribe(t -> strategyEngine.syncSubscriptions());
            })
            .subscribe();

        telegramBot.sendAlert("🌅 *Pre-Market Setup Started (08:30 IST)*\n• Authenticating broker sessions\n• Synchronizing master contract tokens").subscribe();
    }

    /**
     * 09:05 AM IST: Pre-Market Indicator Warm-Up (5m, 15m, 60m).
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onPreMarketWarmup() {
        if (!isTradingDay(clock.get())) return;
        log.info("⏰ 09:05 IST: Triggering Multi-Timeframe Historical Warm-Up (Kite & Shoonya)");

        // 1. Kite Multi-Timeframe Warmup (5m, 15m, 60m)
        List<KiteHistoricalDataService.KiteWarmupRequest> kiteRequests = new ArrayList<>();
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            if (s.isEnabled()) {
                for (String sym : s.getSubscribedSymbols()) {
                    kiteRequests.add(new KiteHistoricalDataService.KiteWarmupRequest(sym, "5", 50));
                    kiteRequests.add(new KiteHistoricalDataService.KiteWarmupRequest(sym, "15", 50));
                    kiteRequests.add(new KiteHistoricalDataService.KiteWarmupRequest(sym, "60", 30));
                }
            }
        }
        if (kiteHistoricalDataService != null && !kiteRequests.isEmpty()) {
            kiteHistoricalDataService.warmupSequentially(kiteRequests)
                .doOnNext(res -> {
                    if (res.success()) {
                        marketDataHub.getCandleAggregator().seedCandles(res.symbol(), res.timeframe(), res.candles());
                    }
                })
                .subscribe();
        }

        // 2. Shoonya Warmup (if enabled)
        List<String> allSymbols = new ArrayList<>();
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            if (s.isEnabled()) {
                allSymbols.addAll(s.getSubscribedSymbols());
            }
        }

        if (historicalDataService != null && !allSymbols.isEmpty()) {
            reactor.core.publisher.Flux.fromIterable(allSymbols)
                .distinct()
                .flatMap(sym -> instrumentMaster.findByCanonicalSymbol(sym)
                    .map(inst -> {
                        String token = inst.shoonyaToken();
                        if (token != null && !token.isBlank()) {
                            String exchange = sym.startsWith("NSE:") ? "NSE" : "NFO";
                            return List.of(
                                new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "5", 50),
                                new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "15", 50),
                                new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "60", 30)
                            );
                        }
                        return List.<ShoonyaHistoricalDataService.HistoricalWarmupRequest>of();
                    })
                    .defaultIfEmpty(List.of())
                )
                .flatMapIterable(list -> list)
                .collectList()
                .flatMap(requests -> {
                    if (!requests.isEmpty()) {
                        return historicalDataService.warmupSequentially(requests)
                            .doOnNext(res -> {
                                if (res.success()) {
                                    marketDataHub.getCandleAggregator().seedCandles(res.symbol(), res.timeframe(), res.candles());
                                }
                            })
                            .then();
                    }
                    return reactor.core.publisher.Mono.empty();
                })
                .subscribe(
                    null,
                    err -> log.warn("Shoonya warmup encountered error: {}", err.getMessage())
                );
        }

        telegramBot.sendAlert("⏰ *Indicator Warm-Up Initiated (09:05 IST)*\n• Warming up multi-timeframe candle buffers (5m, 15m, 1h) for indicators (SuperTrend, RSI, VWAP)").subscribe();
    }

    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onMarketOpen() {
        if (!isTradingDay(clock.get())) return;
        log.info("🔔 09:15 IST: MARKET OPEN — Activating Strategy Engine Event Loop");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_OPEN));
        telegramBot.sendAlert("🔔 *Market Open (09:15 IST)*\n• Strategy Engine Event Loop is ACTIVE\n• Live WebSocket feed receiving ticks").subscribe();
    }

    /**
     * 09:25 AM IST: Option Chain OI Scan & Top 5 Breakout Watchlist Selection.
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onPreMarketOiScan() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 09:25 IST: Triggering Option Chain OI Scan & Top 5 Watchlist Selection");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.OI_SCAN));
        strategyEngine.syncSubscriptions();

        telegramBot.sendAlert("📊 *Top 5 OI Breakout Scan Executed (09:25 IST)*\n• Computed |PE Δ OI| + |CE Δ OI| across F&O universe\n• Selected Top 5 active breakout stocks\n• Live feeds synchronized for 5-minute candle analysis").subscribe();
    }

    /**
     * 09:26 AM IST: Stock Selection Scan for Lowest Volume Reversal Strategy.
     */
    @Scheduled(cron = "0 26 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onStockSelectionScan() {
        if (!isTradingDay(clock.get())) return;
        log.info("📈 09:26 IST: Triggering Stock Selection Scan (Gainers/Losers)");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.STOCK_SELECTION_SCAN));

        reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(5))
            .subscribe(tick -> {
                strategyEngine.syncSubscriptions();
                log.info("📈 09:26 IST: Subscription sync completed after stock selection");
            });

        telegramBot.sendAlert("📈 *Stock Selection Scan (09:26 IST)*\n• Fetching NSE Top Gainers & Losers\n• Identifying reversal candidates for Lowest Volume Reversal strategy").subscribe();
    }

    /**
     * 09:30 AM IST: VWAP Strategy Baseline Snapshot.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onVwapBaseline() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 09:30 IST: VWAP Strategy — Capturing 9:30 Baseline Snapshot");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.VWAP_BASELINE_930));
        telegramBot.sendAlert("📊 *VWAP Baseline Snapshot (09:30 IST)*\n• Capturing Nifty Futures price & PCR\n• Phase 1 of VWAP bias determination").subscribe();
    }

    /**
     * 09:45 AM IST: Iron Fly Entry Scan & Recommendation Generator.
     * Evaluates monthly candidate underlyings at 09:45 AM after opening volatility settles.
     */
    @Scheduled(cron = "0 45 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIronFlyMonthlyScan() {
        if (!isTradingDay(clock.get()) || ironFlyService == null) return;
        log.info("🦅 09:45 IST: Iron Fly Entry Scan & Recommendation Generator");
        ironFlyService.sendRecommendations().subscribe();
    }

    /**
     * 11:00 AM IST: VWAP Strategy Bias Check.
     */
    @Scheduled(cron = "0 0 11 * * MON-FRI", zone = "Asia/Kolkata")
    public void onVwapBiasCheck() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 11:00 IST: VWAP Strategy — Capturing 11:00 Bias Check Snapshot");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.VWAP_BIAS_CHECK_1100));
        telegramBot.sendAlert("📊 *VWAP Bias Check (11:00 IST)*\n• Capturing Nifty Futures price & PCR\n• Phase 2: Bias determination (BULLISH/BEARISH/NEUTRAL)").subscribe();
    }

    /**
     * 15:00 PM IST: Hard Exit for Lowest Volume Reversal Strategy & Daily Iron Fly Position Evaluation.
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onHardExit() {
        if (!isTradingDay(clock.get())) return;
        log.warn("⚡ 15:00 IST: HARD EXIT — Closing all Lowest Volume Reversal positions");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.LVR_HARD_EXIT));
        telegramBot.sendAlert("⚡ *Hard Exit (15:00 IST)*\n• Closing all open positions for Lowest Volume Reversal strategy").subscribe();

        if (ironFlyService != null) {
            log.info("🦅 15:00 IST: Running Iron Fly Daily Position Evaluation");
            ironFlyService.runDailyEvaluation().subscribe();
        }
    }

    /**
     * 15:10 PM IST: Intraday Entry Lock.
     */
    @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIntradayEntryCutoff() {
        if (!isTradingDay(clock.get())) return;
        log.warn("🛑 15:10 IST: INTRADAY ENTRY CUTOFF — Locking all new trade entries");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_ENTRY_CUTOFF));
        telegramBot.sendAlert("🛑 *Intraday Entry Lock (15:10 IST)*\n• All new entry signals are now LOCKED\n• Managing open positions toward 15:14 square-off").subscribe();
    }

    /**
     * 15:14 PM IST: Automated Intraday Square-Off.
     */
    @Scheduled(cron = "0 14 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIntradaySquareOff() {
        if (!isTradingDay(clock.get())) return;
        log.warn("⚡ 15:14 IST: AUTOMATED INTRADAY SQUARE-OFF — Liquidating IntradayBook positions");

        positionManager.executeEodIntradaySquareOff().subscribe();
        telegramBot.sendAlert("⚡ *Automated Intraday Square-Off (15:14 IST)*\n• Liquidating all open IntradayBook (MIS) positions\n• PositionalBook (NRML) remains protected").subscribe();
    }

    /**
     * 15:30 PM IST: Market Close & Daily Reset.
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onMarketClose() {
        if (!isTradingDay(clock.get())) return;
        log.info("15:30 IST: MARKET CLOSED - Resetting Daily RMS & Strategy States");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_CLOSE));
        riskManager.resetDailyStats();

        telegramBot.sendAlert("🌙 *Market Closed (15:30 IST)*\n• Strategy Engine Event Loop DISARMED\n• Daily Risk Limits & Trade Counters RESET").subscribe();
    }

    /**
     * Sets the internal clock supplier (used in tests for deterministic simulation).
     *
     * @param clockSupplier supplier providing simulated local dates
     */
    public void setClock(Supplier<LocalDate> clockSupplier) {
        this.clock = clockSupplier;
    }

    public boolean isWarmupSufficient(String symbol) {
        var buf1m = marketDataHub.getCandleAggregator().getBuffer(symbol, "1");
        var buf5m = marketDataHub.getCandleAggregator().getBuffer(symbol, "5");
        var buf15m = marketDataHub.getCandleAggregator().getBuffer(symbol, "15");
        var buf60m = marketDataHub.getCandleAggregator().getBuffer(symbol, "60");

        int c1 = buf1m.map(b -> b.getLast(50).size()).orElse(0);
        int c5 = buf5m.map(b -> b.getLast(50).size()).orElse(0);
        int c15 = buf15m.map(b -> b.getLast(50).size()).orElse(0);
        int c60 = buf60m.map(b -> b.getLast(30).size()).orElse(0);

        return c1 >= 50 && c5 >= 50 && c15 >= 50 && c60 >= 30;
    }

    public Mono<Double> get1hRsi(String symbol) {
        return fetchCandlesWithFallback(symbol, "60", 30)
            .map(candles -> {
                if (candles == null || candles.size() < 15) return 50.0;
                double[] c = closes(candles);
                double rsi = TechnicalIndicators.calculateRsi(c, 14);
                return Double.isNaN(rsi) ? 50.0 : rsi;
            })
            .defaultIfEmpty(50.0);
    }

    public Mono<Double> get15mSuperTrend(String symbol, int atrLength, double multiplier) {
        return fetchCandlesWithFallback(symbol, "15", 50)
            .map(candles -> {
                if (candles == null || candles.size() < atrLength + 2) return Double.NaN;
                double[] h = highs(candles);
                double[] l = lows(candles);
                double[] c = closes(candles);
                double[] st = TechnicalIndicators.calculateSuperTrend(h, l, c, atrLength, multiplier);
                return st[st.length - 1];
            })
            .defaultIfEmpty(Double.NaN);
    }

    private Mono<List<Candle>> fetchCandlesWithFallback(String symbol, String timeframe, int numCandles) {
        var buffer = marketDataHub.getCandleAggregator().getBuffer(symbol, timeframe);
        if (buffer.isPresent()) {
            List<Candle> cached = buffer.get().getLast(numCandles);
            if (cached != null && cached.size() >= numCandles) {
                return Mono.just(cached);
            }
        }

        if (kiteHistoricalDataService != null) {
            return kiteHistoricalDataService.fetchHistoricalCandles(symbol, timeframe, numCandles)
                .filter(list -> list != null && list.size() >= numCandles)
                .switchIfEmpty(fallbackShoonyaCandles(symbol, timeframe, numCandles));
        }
        return fallbackShoonyaCandles(symbol, timeframe, numCandles);
    }

    private Mono<List<Candle>> fallbackShoonyaCandles(String symbol, String timeframe, int numCandles) {
        return instrumentMaster.findByCanonicalSymbol(symbol)
            .map(Instrument::shoonyaToken)
            .flatMap(token -> {
                if (token == null || token.isBlank()) return Mono.just(List.<Candle>of());
                String exch = symbol.startsWith("NSE:") ? "NSE" : "NFO";
                return historicalDataService.fetchHistoricalCandles(symbol, exch, token, timeframe, numCandles);
            })
            .defaultIfEmpty(List.of());
    }

    private static double[] closes(List<Candle> candles) {
        return candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
    }

    private static double[] highs(List<Candle> candles) {
        return candles.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
    }

    private static double[] lows(List<Candle> candles) {
        return candles.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
    }
}
