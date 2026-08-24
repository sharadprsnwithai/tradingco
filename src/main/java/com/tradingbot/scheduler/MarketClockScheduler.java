package com.tradingbot.scheduler;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.instrument.InstrumentSyncService;
import com.tradingbot.marketdata.KiteHistoricalDataService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.model.Instrument;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.RiskManager;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.strategy.ironfly.IronFlyService;
import com.tradingbot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final TelegramBotService telegramBot;
    private final IronFlyService ironFlyService;

    // Injectable clock for testing — defaults to real IST clock
    private Supplier<LocalDate> clock = () -> LocalDate.now(IST_ZONE);

    // NSE trading holidays — configured via NSE_HOLIDAYS (comma-separated YYYY-MM-DD),
    // defaults to the official NSE 2026 trading holiday list
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
        TelegramBotService telegramBot,
        IronFlyService ironFlyService,
        @Value("${bot.calendar.holidays:2026-01-15,2026-01-26,2026-03-03,2026-03-26,2026-03-31,2026-04-03,2026-04-14,2026-05-01,2026-05-28,2026-06-26,2026-09-14,2026-10-02,2026-10-20,2026-11-10,2026-11-24,2026-12-25}") String holidaysCsv
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
        this.telegramBot = telegramBot;
        this.ironFlyService = ironFlyService;

        initHolidays(holidaysCsv);
    }

    /** Package-private clock setter for testing. */
    void setClock(Supplier<LocalDate> clock) {
        this.clock = clock;
    }

    /**
     * Initializes the set of NSE trading holidays from configuration
     * (comma-separated YYYY-MM-DD dates, defaults to the official 2026 list:
     * Jan 15 Municipal Elections, Jan 26 Republic Day, Mar 03 Holi, Mar 26 Ram Navami,
     * Mar 31 Mahavir Jayanti, Apr 03 Good Friday, Apr 14 Ambedkar Jayanti, May 01
     * Maharashtra Day, May 28 Bakri Eid, Jun 26 Muharram, Sep 14 Ganesh Chaturthi,
     * Oct 02 Gandhi Jayanti, Oct 20 Dussehra, Nov 10 Diwali Balipratipada,
     * Nov 24 Guru Nanak Jayanti, Dec 25 Christmas).
     */
    private void initHolidays(String holidaysCsv) {
        if (holidaysCsv == null || holidaysCsv.isBlank()) return;
        for (String d : holidaysCsv.split(",")) {
            try {
                nseHolidays.add(LocalDate.parse(d.trim()));
            } catch (Exception e) {
                log.warn("Ignoring unparseable holiday date '{}'", d.trim());
            }
        }
        log.info("Loaded {} NSE trading holidays from configuration", nseHolidays.size());
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
     * After brokers authenticate, downloads the Kite instrument master dump into SQLite
     * (required for WebSocket token resolution and option strike lookups), then dispatches
     * the pre-market scan and re-syncs market data subscriptions.
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
                    return null;
                }))
            .then(instrumentSyncService.syncFromKite())
            .doOnNext(count -> log.info("Instrument master synced: {} instruments", count))
            .onErrorResume(e -> {
                log.error("Instrument sync failed ({}), continuing with pre-market dispatch", e.getMessage());
                return reactor.core.publisher.Mono.empty();
            })
            .doOnTerminate(() -> {
                strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.PRE_MARKET_SCAN));
                // Re-sync subscriptions now that instrument tokens exist
                reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(2))
                    .subscribe(t -> strategyEngine.syncSubscriptions());
            })
            .subscribe();

        telegramBot.sendAlert("🌅 *Pre-Market Setup Started (08:30 IST)*\n• Authenticating broker sessions\n• Synchronizing master contract tokens").subscribe();
    }

    /**
     * 09:05 AM IST: Pre-Market Indicator Warm-Up via Shoonya TPSeries (350ms throttle).
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
        List<ShoonyaHistoricalDataService.HistoricalWarmupRequest> requests = new ArrayList<>();
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            if (s.isEnabled()) {
                for (String sym : s.getSubscribedSymbols()) {
                    String token = instrumentMaster.findByCanonicalSymbol(sym)
                        .map(Instrument::shoonyaToken)
                        .block();
                    if (token != null && !token.isBlank()) {
                        String exchange = sym.startsWith("NSE:") ? "NSE" : "NFO";
                        requests.add(new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "5", 50));
                        requests.add(new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "15", 50));
                        requests.add(new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, exchange, token, "60", 30));
                    } else {
                        log.warn("No Shoonya token found for {}, skipping Shoonya warmup", sym);
                    }
                }
            }
        }

        historicalDataService.warmupSequentially(requests)
            .doOnNext(res -> {
                if (res.success()) {
                    marketDataHub.getCandleAggregator().seedCandles(res.symbol(), res.timeframe(), res.candles());
                }
            })
            .subscribe();

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
     * Fetches NSE Top Gainers/Losers and dispatches STOCK_SELECTION_SCAN event.
     * Then syncs subscriptions to pick up new symbols.
     */
    @Scheduled(cron = "0 26 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onStockSelectionScan() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 09:26 IST: Triggering Stock Selection Scan (Gainers/Losers)");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.STOCK_SELECTION_SCAN));

        // Delay sync to allow async NSE API calls to complete, then sync new symbols
        reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(5))
            .subscribe(tick -> {
                strategyEngine.syncSubscriptions();
                log.info("📊 09:26 IST: Subscription sync completed after stock selection");
            });

        telegramBot.sendAlert("📊 *Stock Selection Scan (09:26 IST)*\n• Fetching NSE Top Gainers & Losers\n• Identifying reversal candidates for Lowest Volume Reversal strategy").subscribe();
    }

    /**
     * 09:30 AM IST: VWAP Strategy Baseline Snapshot.
     * Captures Nifty Futures price and PCR for VWAP strategy bias determination.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onVwapBaseline() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 09:30 IST: VWAP Strategy — Capturing 9:30 Baseline Snapshot");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.VWAP_BASELINE_930));
        telegramBot.sendAlert("📊 *VWAP Baseline Snapshot (09:30 IST)*\n• Capturing Nifty Futures price & PCR\n• Phase 1 of VWAP bias determination").subscribe();
    }

    /**
     * 11:00 AM IST: VWAP Strategy Bias Check.
     * Captures 11:00 Nifty Futures price and PCR, determines bullish/bearish/neutral bias.
     */
    @Scheduled(cron = "0 0 11 * * MON-FRI", zone = "Asia/Kolkata")
    public void onVwapBiasCheck() {
        if (!isTradingDay(clock.get())) return;
        log.info("📊 11:00 IST: VWAP Strategy — Capturing 11:00 Bias Check Snapshot");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.VWAP_BIAS_CHECK_1100));
        telegramBot.sendAlert("📊 *VWAP Bias Check (11:00 IST)*\n• Capturing Nifty Futures price & PCR\n• Phase 2: Bias determination (BULLISH/BEARISH/NEUTRAL)").subscribe();
    }

    /**
     * 15:00 PM IST: Hard Exit for Lowest Volume Reversal Strategy.
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onHardExit() {
        if (!isTradingDay(clock.get())) return;
        log.warn("⚡ 15:00 IST: HARD EXIT — Closing all Lowest Volume Reversal positions");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_SQUARE_OFF));
        telegramBot.sendAlert("⚡ *Hard Exit (15:00 IST)*\n• Closing all open positions for Lowest Volume Reversal strategy").subscribe();
    }

    /**
     * 15:10 PM IST: Intraday Entry Lock.
     */
    @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIntradayEntryCutoff() {
        if (!isTradingDay(clock.get())) return;
        log.warn("🔒 15:10 IST: INTRADAY ENTRY CUTOFF — Locking all new trade entries");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_ENTRY_CUTOFF));
        telegramBot.sendAlert("🔒 *Intraday Entry Lock (15:10 IST)*\n• All new entry signals are now LOCKED\n• Managing open positions toward 15:14 square-off").subscribe();
    }

    /**
     * 15:14 PM IST: Automated Intraday Square-Off (User Requirement: 15:14 IST).
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
        telegramBot.sendAlert("\ud83c\udfc1 *Market Closed (15:30 IST)*\n\u2022 Trading session ended\n\u2022 Daily risk metrics & strategy states reset").subscribe();
    }

    /**
     * 09:30 AM IST: Iron Fly Entry Recommendations.
     * Sends Telegram recommendations for configured underlyings.
     */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIronFlyRecommendation() {
        if (!isTradingDay(clock.get())) return;
        log.info("09:30 IST: Iron Fly - Sending entry recommendations");
        ironFlyService.sendRecommendations().subscribe();
    }

    /**
     * 10:00 AM IST: Iron Fly Position Discovery.
     * Fetches broker positions and starts tracking discovered Iron Fly legs.
     */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIronFlyDiscovery() {
        if (!isTradingDay(clock.get())) return;
        log.info("10:00 IST: Iron Fly - Discovering positions from broker");
        ironFlyService.discoverPositions().subscribe();
    }

    /**
     * 15:00 PM IST: Iron Fly Daily Evaluation.
     * Evaluates all tracked positions for profit target, stop loss, expiry, and decay triggers.
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIronFlyEvaluation() {
        if (!isTradingDay(clock.get())) return;
        log.info("15:00 IST: Iron Fly - Running daily evaluation");
        ironFlyService.runDailyEvaluation().subscribe();
    }
}
