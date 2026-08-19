package com.tradingbot.scheduler;

import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.marketdata.ShoonyaHistoricalDataService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.RiskManager;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final TelegramBotService telegramBot;

    // NSE 2025 Trading Holidays
    private final Set<LocalDate> nseHolidays = new HashSet<>();

    public MarketClockScheduler(
        StrategyEngine strategyEngine,
        PositionManagerService positionManager,
        RiskManager riskManager,
        BrokerAdapterRegistry brokerRegistry,
        MarketDataHub marketDataHub,
        ShoonyaHistoricalDataService historicalDataService,
        TelegramBotService telegramBot
    ) {
        this.strategyEngine = strategyEngine;
        this.positionManager = positionManager;
        this.riskManager = riskManager;
        this.brokerRegistry = brokerRegistry;
        this.marketDataHub = marketDataHub;
        this.historicalDataService = historicalDataService;
        this.telegramBot = telegramBot;

        initHolidays();
    }

    /**
     * Initializes the set of known NSE trading holidays for 2025.
     */
    private void initHolidays() {
        // Known 2025 Exchange Holidays (YYYY, MM, DD)
        nseHolidays.add(LocalDate.of(2025, 2, 26));  // Mahashivratri
        nseHolidays.add(LocalDate.of(2025, 3, 14));  // Holi
        nseHolidays.add(LocalDate.of(2025, 3, 31));  // Id-Ul-Fitr
        nseHolidays.add(LocalDate.of(2025, 4, 10));  // Mahavir Jayanti
        nseHolidays.add(LocalDate.of(2025, 4, 14));  // Dr. Baba Saheb Ambedkar Jayanti
        nseHolidays.add(LocalDate.of(2025, 4, 18));  // Good Friday
        nseHolidays.add(LocalDate.of(2025, 5, 1));   // Maharashtra Day
        nseHolidays.add(LocalDate.of(2025, 8, 15));  // Independence Day
        nseHolidays.add(LocalDate.of(2025, 8, 27));  // Ganesh Chaturthi
        nseHolidays.add(LocalDate.of(2025, 10, 2));  // Mahatma Gandhi Jayanti / Dussehra
        nseHolidays.add(LocalDate.of(2025, 10, 21)); // Diwali Laxmi Pujan
        nseHolidays.add(LocalDate.of(2025, 10, 22)); // Diwali Balipratipada
        nseHolidays.add(LocalDate.of(2025, 11, 5));  // Gurunanak Jayanti
        nseHolidays.add(LocalDate.of(2025, 12, 25)); // Christmas
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
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.info("⏰ 08:30 IST: Triggering Pre-Market Broker Authentication & Master Sync");

        brokerRegistry.getAll()
            .flatMap(adapter -> adapter.authenticate()
                .doOnSuccess(v -> log.info("Authenticated broker: {}", adapter.getBrokerId()))
                .onErrorResume(e -> {
                    log.error("Failed to authenticate {}: {}", adapter.getBrokerId(), e.getMessage());
                    return null;
                }))
            .subscribe();

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.PRE_MARKET_SCAN));
        telegramBot.sendAlert("🌅 *Pre-Market Setup Started (08:30 IST)*\n• Authenticating broker sessions\n• Synchronizing master contract tokens").subscribe();
    }

    /**
     * 09:05 AM IST: Pre-Market Indicator Warm-Up via Shoonya TPSeries (350ms throttle).
     */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onPreMarketWarmup() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.info("⏰ 09:05 IST: Triggering Historical Indicator Warm-Up (350ms Sequential Throttle)");

        List<ShoonyaHistoricalDataService.HistoricalWarmupRequest> requests = new ArrayList<>();
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            if (s.isEnabled()) {
                for (String sym : s.getSubscribedSymbols()) {
                    requests.add(new ShoonyaHistoricalDataService.HistoricalWarmupRequest(sym, "NSE", "2885", "5", 50));
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

        telegramBot.sendAlert("📊 *Indicator Warm-Up Initiated (09:05 IST)*\n• Warming up " + requests.size() + " symbol historical buffers via Shoonya TPSeries").subscribe();
    }

    /**
     * 09:15 AM IST: Market Open Trigger.
     */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onMarketOpen() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.info("🔔 09:15 IST: MARKET OPEN — Activating Strategy Engine Event Loop");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_OPEN));
        telegramBot.sendAlert("🔔 *Market Open (09:15 IST)*\n• Strategy Engine Event Loop is ACTIVE\n• Live WebSocket feed receiving ticks").subscribe();
    }

    /**
     * 09:25 AM IST: Option Chain OI Scan & Top 5 Breakout Watchlist Selection.
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void onPreMarketOiScan() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.info("📊 09:25 IST: Triggering Option Chain OI Scan & Top 5 Watchlist Selection");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.OI_SCAN));
        strategyEngine.syncSubscriptions();

        telegramBot.sendAlert("📊 *Top 5 OI Breakout Scan Executed (09:25 IST)*\n• Computed |PE Δ OI| + |CE Δ OI| across F&O universe\n• Selected Top 5 active breakout stocks\n• Live feeds synchronized for 5-minute candle analysis").subscribe();
    }

    /**
     * 15:10 PM IST: Intraday Entry Lock.
     */
    @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIntradayEntryCutoff() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.warn("🔒 15:10 IST: INTRADAY ENTRY CUTOFF — Locking all new trade entries");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_ENTRY_CUTOFF));
        telegramBot.sendAlert("🔒 *Intraday Entry Lock (15:10 IST)*\n• All new entry signals are now LOCKED\n• Managing open positions toward 15:14 square-off").subscribe();
    }

    /**
     * 15:14 PM IST: Automated Intraday Square-Off (User Requirement: 15:14 IST).
     */
    @Scheduled(cron = "0 14 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onIntradaySquareOff() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.warn("⚡ 15:14 IST: AUTOMATED INTRADAY SQUARE-OFF — Liquidating IntradayBook positions");

        positionManager.executeEodIntradaySquareOff().subscribe();
        telegramBot.sendAlert("⚡ *Automated Intraday Square-Off (15:14 IST)*\n• Liquidating all open IntradayBook (MIS) positions\n• PositionalBook (NRML) remains protected").subscribe();
    }

    /**
     * 15:30 PM IST: Market Close & Daily Reset.
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void onMarketClose() {
        if (!isTradingDay(LocalDate.now(IST_ZONE))) return;
        log.info("🏁 15:30 IST: MARKET CLOSED — Resetting Daily RMS & Strategy States");

        strategyEngine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_CLOSE));
        riskManager.resetDailyStats();
        telegramBot.sendAlert("🏁 *Market Closed (15:30 IST)*\n• Trading session ended\n• Daily risk metrics & strategy states reset").subscribe();
    }
}
