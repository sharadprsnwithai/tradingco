package com.tradingbot.strategy.impl;

import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.instrument.LotSizeService;
import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.KitePcrProvider;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nifty 9:30 Baseline + 5-Min VWAP Cross Intraday Options Buying Strategy.
 *
 * Logic:
 * Phase 1: 9:30 AM — capture Nifty Futures baseline and PCR snapshot.
 * Phase 2: 11:00 AM — recheck Nifty Futures and PCR, determine bullish/bearish/neutral bias.
 * Phase 3: After 11:00 AM — monitor 5-min candles against session VWAP.
 *          Bullish: candle closes above VWAP (with Low below VWAP, green candle) → Buy ATM CE.
 *          Bearish: candle closes below VWAP (with High above VWAP, red candle) → Buy ATM PE.
 * Phase 4: Exit via target (+40 pts), initial SL (-20 pts), VWAP cross exit, or trailing SL.
 * Phase 5: 3:14 PM — hard square-off all positions.
 *
 * @see <a href="Nifty_VWAP_Momentum_Reversal_Strategy.md">Strategy Specification</a>
 */
@Component
public class NiftyVwapMomentumReversalStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(NiftyVwapMomentumReversalStrategy.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String TIMEFRAME = "5";
    private static final int LOT_SIZE = 65;
    private static final int LOTS_PER_ENTRY = 2;
    private static final int QTY_PER_ENTRY = LOT_SIZE * LOTS_PER_ENTRY;
    private static final double TARGET_POINTS = 40.0;
    private static final double INITIAL_SL_POINTS = 20.0;
    private static final double BREAKEVEN_LOCK_POINTS = 0.0;
    private static final int MAX_ENTRIES_PER_DAY = 3;
    private static final int GRACE_CANDLES = 2;

    private final String strategyId;
    private final String assignedAccountId;
    /** Configured fallback symbol, used in backtest/legacy mode (no instrument services). */
    private final String symbol;
    /**
     * Trigger tolerance (in underlying points). Adds hysteresis to the VWAP-cross entry,
     * the VWAP-cross exit, and the 930/1100 bias snapshot so that tiny broker-feed
     * differences (a few paise in OHLC) don't flip signals. 0 = exact (legacy) behaviour.
     */
    private final double triggerTolerance;
    private final KitePcrProvider kitePcrProvider;
    private final InstrumentMasterService instrumentMaster;
    private final LotSizeService lotSizeService;

    /** Optional — present only in the live Spring context; null in backtest/manual construction. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TradingDbService tradingDbService;

    /** Dynamic subscription list: resolved NIFTY futures + any active option contract. */
    private final List<String> symbols = new CopyOnWriteArrayList<>();
    /** Resolved NIFTY futures canonical symbol (set at PRE_MARKET_SCAN in live mode). */
    private volatile String underlyingSymbol;

    private StrategyContext context;
    private volatile boolean enabled = true;

    // Daily state
    private final DailyState daily = new DailyState();

    /**
     * Spring bean constructor with instrument services for real option resolution.
     */
    @Autowired
    public NiftyVwapMomentumReversalStrategy(
        @Value("${bot.strategies.vwap-nifty.id:VWAP_NIFTY_01}") String strategyId,
        @Value("${bot.strategies.vwap-nifty.account-id:KITE_USER_01}") String assignedAccountId,
        @Value("${bot.strategies.vwap-nifty.symbol:NFO:NIFTY_FUT}") String symbol,
        KitePcrProvider kitePcrProvider,
        InstrumentMasterService instrumentMaster,
        LotSizeService lotSizeService
    ) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.symbol = symbol;
        this.triggerTolerance = 0.0;
        this.kitePcrProvider = kitePcrProvider;
        this.instrumentMaster = instrumentMaster;
        this.lotSizeService = lotSizeService;
        this.underlyingSymbol = symbol;
        this.symbols.add(symbol);
    }

    /**
     * Simple constructor for backtest (no Kite PCR, no instrument services — legacy mode
     * where the configured symbol's candles double as the premium series).
     * @param triggerTolerance hysteresis band (underlying points) for VWAP-cross and bias.
     */
    public NiftyVwapMomentumReversalStrategy(String strategyId, String assignedAccountId, String symbol,
                                             double triggerTolerance) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.symbol = symbol;
        this.triggerTolerance = triggerTolerance;
        this.kitePcrProvider = null;
        this.instrumentMaster = null;
        this.lotSizeService = null;
        this.underlyingSymbol = symbol;
        this.symbols.add(symbol);
    }

    /**
     * Simple constructor for backtest (no Kite PCR, no instrument services — legacy mode
     * where the configured symbol's candles double as the premium series).
     */
    public NiftyVwapMomentumReversalStrategy(String strategyId, String assignedAccountId, String symbol) {
        this(strategyId, assignedAccountId, symbol, 0.0);
    }

    /** Live mode = real instrument services available (option resolution + premium quotes). */
    private boolean isLiveMode() {
        return instrumentMaster != null && kitePcrProvider != null;
    }

    @Override
    public String getStrategyId() { return strategyId; }

    @Override
    public String getAssignedAccountId() { return assignedAccountId; }

    @Override
    public java.util.List<String> getSubscribedSymbols() { return java.util.Collections.unmodifiableList(symbols); }

    @Override
    public void init(StrategyContext context) {
        this.context = context;
        // Note: daily state is already clean from constructor or destroy().
        // Don't reset here — backtest runner may have set baselines before calling this.
    }

    @Override
    public void onTick(Tick tick) {
        if (!enabled || tick == null) return;
        // Premium exits are driven by OPTION ticks in live mode, configured-symbol ticks in legacy mode
        String premiumSymbol = daily.activeOptionSymbol != null ? daily.activeOptionSymbol : symbol;
        if (!premiumSymbol.equals(tick.symbol())) return;
        synchronized (daily) {
            if (daily.activeOptionSymbol != null && daily.activeOptionSymbol.equals(tick.symbol())) {
                daily.lastPremiumLtp = tick.ltp().doubleValue();
            }
            if (daily.position == Position.IN_TRADE) {
                checkTickExits(tick);
            }
        }
    }

    @Override
    public void onCandle(Candle candle) {
        if (!enabled || candle == null || !TIMEFRAME.equals(candle.timeframe())) return;
        if (!underlyingSymbol.equals(candle.symbol())) return;

        synchronized (daily) {
            daily.allCandles.add(candle);

            // Update VWAP accumulator
            updateVwap(candle);

            // Check exits first if in trade
            if (daily.position == Position.IN_TRADE) {
                daily.candlesSinceEntry++;
                checkCandleExits(candle);
            }

            // Evaluate entry if bias is confirmed and we have slots
            if (daily.bias != Bias.NEUTRAL
                && daily.position == Position.FLAT
                && daily.entriesToday < MAX_ENTRIES_PER_DAY
                && !daily.entryLocked) {
                evaluateEntry(candle);
            }
        }
    }

    @Override
    public void onSchedule(ScheduledEvent event) {
        switch (event.eventType()) {
            case ScheduledEvent.PRE_MARKET_SCAN -> {
                daily.reset();
                resolveUnderlyingFuture();
            }
            case ScheduledEvent.MARKET_OPEN -> {
                daily.vwapReady = false;
            }
            case ScheduledEvent.VWAP_BASELINE_930 -> {
                // 9:30 AM: Capture Nifty Futures price + PCR snapshot
                captureBaseline930();
            }
            case ScheduledEvent.VWAP_BIAS_CHECK_1100 -> {
                // 11:00 AM: Capture Nifty Futures price + PCR, determine bias
                captureBiasCheck1100();
            }
            case ScheduledEvent.VWAP_RECOVER -> {
                // Startup/history-backfill recovery: reconstruct any 9:30 / 11:00 snapshots
                // missed because the process started mid-day (those cron events already passed).
                recoverBaselinesIfNeeded();
            }
            case ScheduledEvent.INTRADAY_ENTRY_CUTOFF -> daily.entryLocked = true;
            case ScheduledEvent.INTRADAY_SQUARE_OFF -> squareOffAll("EOD_SQUARE_OFF");
            case ScheduledEvent.MARKET_CLOSE -> daily.reset();
        }
    }

    /**
     * Periodic diagnostic (every 5 min during market hours) so operators can see exactly
     * what data the strategy holds and why it is / isn't generating signals. Surfaces the
     * 5m candle buffer size, the live VWAP, the 9:30/11:00 bias snapshots, and the gating
     * state. The most common mid-day-restart failure is {@code bias=NEUTRAL} because the
     * 9:30 and 11:00 scheduled snapshots never fired for this process — this log makes that
     * immediately obvious.
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void logDiagnostic() {
        if (!enabled || context == null) return;
        try {
            String sym = underlyingSymbol;
            List<Candle> fiveMin = context.getHistoricalCandles(sym, TIMEFRAME, 200);
            if (fiveMin == null) fiveMin = Collections.emptyList();
            Candle last = fiveMin.isEmpty() ? null : fiveMin.get(fiveMin.size() - 1);
            double lastClose = last != null ? last.close().doubleValue() : 0.0;
            double vwap = getVwap();
            boolean isGreen = last != null && last.close().compareTo(last.open()) > 0;
            boolean isRed = last != null && last.close().compareTo(last.open()) < 0;

            String gate;
            if (vwap <= 0) {
                gate = "VWAP_NOT_READY";
            } else if (daily.bias == Bias.NEUTRAL) {
                gate = "BIAS_NEUTRAL(missed 9:30/11:00 snapshots)";
            } else if (daily.position != Position.FLAT) {
                gate = "IN_TRADE";
            } else if (daily.entryLocked) {
                gate = "ENTRY_LOCKED";
            } else if (daily.entriesToday >= MAX_ENTRIES_PER_DAY) {
                gate = "MAX_ENTRIES(" + daily.entriesToday + ")";
            } else {
                boolean cond = (daily.bias == Bias.BULLISH && isGreen
                        && lastClose > vwap + triggerTolerance && last != null && last.low().doubleValue() < vwap)
                    || (daily.bias == Bias.BEARISH && isRed
                        && lastClose < vwap - triggerTolerance && last != null && last.high().doubleValue() > vwap);
                gate = cond ? "WOULD_ENTER(" + daily.bias + ")" : "CONDITIONS_NOT_MET";
            }

            log.info("[{}] DIAG | sym={} | buffer5m={} | vwapCandles={} | lastClose={} | vwap={} (ready={}) | "
                    + "bias={} | pos={} | entries={}/{} | locked={} | "
                    + "930[price={},pcr={},done={}] 1100[price={},pcr={},done={}] | gate={}",
                strategyId, sym, fiveMin.size(), daily.allCandles.size(), round2(lastClose), round2(vwap),
                daily.vwapReady, daily.bias, daily.position, daily.entriesToday, MAX_ENTRIES_PER_DAY, daily.entryLocked,
                round2(daily.nifty930), round2(daily.pcr930), daily.snapshot930Done,
                round2(daily.nifty1100), round2(daily.pcr1100), daily.snapshot1100Done,
                gate);
        } catch (Exception e) {
            log.warn("[{}] DIAG failed: {}", strategyId, e.getMessage());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Resolves the real nearest-expiry NIFTY futures contract from the instrument master
     * (requires the 08:30 IST instrument sync). In backtest/legacy mode (no instrument
     * services) the configured symbol is kept as the underlying.
     */
    private void resolveUnderlyingFuture() {
        if (instrumentMaster == null) return;
        try {
            var fut = instrumentMaster.findNearestExpiring("NIFTY", "FUT").blockOptional();
            if (fut.isPresent()) {
                underlyingSymbol = fut.get().canonicalSymbol();
                symbols.clear();
                symbols.add(underlyingSymbol);
                if (context != null) context.requestSubscriptionSync();
                log.info("[{}] Resolved NIFTY futures underlying: {}", strategyId, underlyingSymbol);
            } else {
                underlyingSymbol = symbol;
                log.warn("[{}] No NIFTY FUT in instrument master (sync pending?) - keeping {}", strategyId, symbol);
            }
        } catch (Exception e) {
            underlyingSymbol = symbol;
            log.warn("[{}] Failed to resolve NIFTY futures: {} - keeping {}", strategyId, e.getMessage(), symbol);
        }
    }

    @Override
    public void destroy() { daily.reset(); }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // ========== VWAP Calculation ==========

    private void updateVwap(Candle candle) {
        double tp = (candle.high().doubleValue() + candle.low().doubleValue() + candle.close().doubleValue()) / 3.0;
        double vol = candle.volume();
        if (vol <= 0) vol = 1; // avoid division by zero for index-like instruments

        daily.cumulativeTpVol += tp * vol;
        daily.cumulativeVol += vol;
        daily.vwapReady = true;
    }

    /**
     * Returns VWAP computed from candles before the given candle (candle N uses candles 1..N-1).
     * If fewer than 2 candles have been recorded, returns 0 (VWAP not meaningful yet).
     */
    private double getVwapBeforeCurrentCandle() {
        if (daily.cumulativeVol <= 0) return 0;
        // Subtract the last candle's contribution to get VWAP of prior candles
        Candle last = daily.allCandles.isEmpty() ? null : daily.allCandles.get(daily.allCandles.size() - 1);
        if (last == null) return 0;

        double lastTp = (last.high().doubleValue() + last.low().doubleValue() + last.close().doubleValue()) / 3.0;
        double lastVol = last.volume();
        if (lastVol <= 0) lastVol = 1;

        double prevTpVol = daily.cumulativeTpVol - (lastTp * lastVol);
        double prevVol = daily.cumulativeVol - lastVol;

        if (prevVol <= 0) return 0;
        return prevTpVol / prevVol;
    }

    // ========== 9:30 / 11:00 Snapshot Logic ==========

    private boolean isTime(int hour, int minute) {
        if (context == null) return false;
        java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(context.now(), java.time.ZoneId.of("Asia/Kolkata"));
        return ldt.getHour() == hour && ldt.getMinute() == minute;
    }

    private boolean isAfterTime(int hour, int minute) {
        if (context == null) return false;
        java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(context.now(), java.time.ZoneId.of("Asia/Kolkata"));
        return ldt.getHour() > hour || (ldt.getHour() == hour && ldt.getMinute() >= minute);
    }

    private boolean isBeforeTime(int hour, int minute) {
        if (context == null) return false;
        java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(context.now(), java.time.ZoneId.of("Asia/Kolkata"));
        return ldt.getHour() < hour || (ldt.getHour() == hour && ldt.getMinute() < minute);
    }

    // ========== Entry Logic ==========

    private void evaluateEntry(Candle candle) {
        double vwap = getVwapBeforeCurrentCandle();
        if (vwap <= 0) return;

        boolean isGreen = candle.close().compareTo(candle.open()) > 0;
        boolean isRed = candle.close().compareTo(candle.open()) < 0;

        double close = candle.close().doubleValue();
        double high = candle.high().doubleValue();
        double low = candle.low().doubleValue();

        Direction entryDir = null;

        if (daily.bias == Bias.BULLISH && isGreen) {
            // Require close to clear VWAP by the tolerance band (avoids borderline-flip entries).
            if (close > vwap + triggerTolerance && low < vwap) {
                entryDir = Direction.LONG;
            }
        } else if (daily.bias == Bias.BEARISH && isRed) {
            if (close < vwap - triggerTolerance && high > vwap) {
                entryDir = Direction.SHORT;
            }
        }

        if (entryDir != null) {
            if (isLiveMode()) {
                enterLiveTrade(entryDir, close, candle.timestamp());
            } else {
                // Legacy/backtest mode: configured symbol doubles as the premium series
                daily.enterTrade(entryDir, close, vwap, candle.timestamp());
                String tag = "VWAP_" + entryDir + "_ENTRY";
                Signal entrySignal = Signal.builder()
                    .strategyId(strategyId)
                    .targetAccountId(assignedAccountId)
                    .symbol(symbol)
                    .signalType(entryDir == Direction.LONG ? SignalType.ENTRY_LONG : SignalType.ENTRY_SHORT)
                    .quantity(QTY_PER_ENTRY)
                    .price(BigDecimal.valueOf(daily.entryPremium))
                    .orderType(OrderType.MARKET)
                    .productType(ProductType.MIS)
                    .tag(tag)
                    .timestamp(candle.timestamp())
                    .build();
                context.emitSignal(entrySignal);
            }
        }
    }

    /**
     * Live-mode entry: resolve the real ATM option for the current futures price,
     * subscribe it (so premium ticks flow for exits), fetch its LTP as the entry
     * premium, and emit an ENTRY_LONG signal (options BUYING for both CE and PE)
     * carrying an exchange-side protective stop trigger.
     */
    private void enterLiveTrade(Direction dir, double futuresClose, Instant time) {
        String optionType = dir == Direction.LONG ? "CE" : "PE";
        try {
            var opt = instrumentMaster.findNearestAtmOption("NIFTY", futuresClose, optionType).blockOptional();
            if (opt.isEmpty()) {
                log.warn("[{}] No ATM {} option found near futures price {} - entry skipped", strategyId, optionType, futuresClose);
                return;
            }
            String optionSymbol = opt.get().canonicalSymbol();

            double premium = kitePcrProvider.fetchLtp(optionSymbol);
            if (premium <= 0) {
                log.warn("[{}] No premium quote for {} - entry skipped (no blind orders)", strategyId, optionSymbol);
                return;
            }

            int qty = lotSizeService != null ? lotSizeService.getOrderQuantity("NIFTY") : QTY_PER_ENTRY;

            if (!symbols.contains(optionSymbol)) {
                symbols.add(optionSymbol);
                context.requestSubscriptionSync();
            }

            daily.activeOptionSymbol = optionSymbol;
            daily.positionQty = qty;
            daily.longPremium = true;
            daily.lastPremiumLtp = premium;
            daily.enterTrade(dir, premium, futuresClose, time);

            String tag = "VWAP_" + optionType + "_ENTRY";
            Signal entrySignal = Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(optionSymbol)
                .signalType(SignalType.ENTRY_LONG) // buying the option (CE or PE)
                .quantity(qty)
                .price(BigDecimal.valueOf(premium))
                .orderType(OrderType.LIMIT) // OMS derives marketable limit (spec: avoid pure market slippage)
                .productType(ProductType.MIS)
                .protectiveStopTrigger(BigDecimal.valueOf(premium - INITIAL_SL_POINTS))
                .tag(tag)
                .timestamp(time)
                .build();
            context.emitSignal(entrySignal);

            log.info("[{}] LIVE ENTRY: {} x {} @ premium {} (fut {}) | SL trigger {} | qty {}",
                strategyId, optionSymbol, qty, premium, futuresClose, premium - INITIAL_SL_POINTS, qty);
        } catch (Exception e) {
            log.error("[{}] Live entry resolution failed: {}", strategyId, e.getMessage(), e);
        }
    }

    // ========== Exit Logic ==========

    private void checkCandleExits(Candle candle) {
        double vwap = getVwapBeforeCurrentCandle();
        if (vwap <= 0) return;

        double close = candle.close().doubleValue();

        // Rule A: VWAP cross exit (after grace period) — always evaluated on FUTURES candles.
        // tradeDirection tracks the BIAS side: LONG = bullish (bought CE), SHORT = bearish (bought PE).
        // triggerTolerance requires a clear penetration so a single noisy wick can't flip the exit.
        if (daily.candlesSinceEntry > GRACE_CANDLES) {
            if (daily.tradeDirection == Direction.LONG && close < vwap - triggerTolerance) {
                exitTrade(exitPriceForCandle(close), "VWAP_CROSS_BELOW", candle.timestamp());
                return;
            }
            if (daily.tradeDirection == Direction.SHORT && close > vwap + triggerTolerance) {
                exitTrade(exitPriceForCandle(close), "VWAP_CROSS_ABOVE", candle.timestamp());
                return;
            }
        }

        // Breakeven lock and trailing SL disabled — SL stays fixed at entryPremium +/- INITIAL_SL_POINTS
        if (daily.longPremium) return;

        if (daily.tradeDirection == Direction.LONG) {
            // SL fixed at entryPremium - INITIAL_SL_POINTS
        } else if (daily.tradeDirection == Direction.SHORT) {
            // SL fixed at entryPremium + INITIAL_SL_POINTS
        }
    }

    /**
     * Exit price for candle-triggered exits: last known option premium in live mode,
     * candle close in legacy mode (where the candle series IS the premium proxy).
     */
    private double exitPriceForCandle(double candleClose) {
        if (daily.longPremium) {
            return daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.currentSlPremium;
        }
        return candleClose;
    }

    private void checkTickExits(Tick tick) {
        double ltp = tick.ltp().doubleValue();

        // Live mode: both CE and PE are BOUGHT options — long-premium logic for both.
        // Target is premium +40, protective stop is premium -20, ALWAYS active (with
        // breakeven trailing once halfway to target). VWAP cross is the candle-based exit.
        if (daily.longPremium) {
            if (ltp >= daily.entryPremium + TARGET_POINTS) {
                exitTrade(ltp, "TARGET_HIT", tick.timestamp());
                return;
            }
            // Breakeven trailing: once halfway to target, lock stop to entry.
            if (ltp >= daily.entryPremium + TARGET_POINTS / 2.0
                && daily.currentSlPremium < daily.entryPremium) {
                daily.currentSlPremium = daily.entryPremium;
            }
            if (ltp <= daily.currentSlPremium) {
                exitTrade(ltp, "INITIAL_SL_HIT", tick.timestamp());
                return;
            }
            if (isAfterTime(15, 14)) {
                exitTrade(ltp, "EOD_HARD_EXIT", tick.timestamp());
            }
            return;
        }

        if (daily.tradeDirection == Direction.LONG) {
            if (ltp >= daily.entryPremium + TARGET_POINTS) {
                exitTrade(ltp, "TARGET_HIT", tick.timestamp());
                return;
            }
            if (ltp >= daily.entryPremium + TARGET_POINTS / 2.0
                && daily.currentSlPremium < daily.entryPremium) {
                daily.currentSlPremium = daily.entryPremium;
            }
            if (ltp <= daily.currentSlPremium) {
                exitTrade(ltp, "INITIAL_SL_HIT", tick.timestamp());
                return;
            }
        } else if (daily.tradeDirection == Direction.SHORT) {
            if (ltp <= daily.entryPremium - TARGET_POINTS) {
                exitTrade(ltp, "TARGET_HIT", tick.timestamp());
                return;
            }
            if (ltp <= daily.entryPremium - TARGET_POINTS / 2.0
                && daily.currentSlPremium > daily.entryPremium) {
                daily.currentSlPremium = daily.entryPremium;
            }
            if (ltp >= daily.currentSlPremium) {
                exitTrade(ltp, "INITIAL_SL_HIT", tick.timestamp());
                return;
            }
        }

        // Check EOD square-off time (3:14 PM IST)
        if (isAfterTime(15, 14)) {
            exitTrade(ltp, "EOD_HARD_EXIT", tick.timestamp());
        }
    }

    private void squareOffAll(String reason) {
        if (daily.position == Position.IN_TRADE) {
            // Use last known price — for backtest this is the last candle close
            double lastPrice = daily.allCandles.isEmpty() ? daily.entryPremium
                : daily.allCandles.get(daily.allCandles.size() - 1).close().doubleValue();
            exitTrade(lastPrice, reason, context.now());
        }
    }

    private void exitTrade(double exitPrice, String reason, java.time.Instant timestamp) {
        String exitSymbol = daily.activeOptionSymbol != null ? daily.activeOptionSymbol : symbol;
        int qty = daily.positionQty > 0 ? daily.positionQty : QTY_PER_ENTRY;

        double pnl;
        if (daily.longPremium || daily.tradeDirection == Direction.LONG) {
            pnl = (exitPrice - daily.entryPremium) * qty;
        } else {
            pnl = (daily.entryPremium - exitPrice) * qty;
        }

        String tag = "VWAP_" + daily.tradeDirection + "_" + reason;
        Signal exitSignal = Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(exitSymbol)
            .signalType(daily.longPremium || daily.tradeDirection == Direction.LONG
                ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT) // bought options always SELL to close
            .quantity(qty)
            .price(BigDecimal.valueOf(exitPrice))
            .orderType(daily.longPremium ? OrderType.LIMIT : OrderType.MARKET) // live: marketable limit on premium
            .productType(ProductType.MIS)
            .tag(tag)
            .timestamp(timestamp)
            .build();
        context.emitSignal(exitSignal);

        log.info("[{}] EXIT {} {} @ {} | Entry: {} | P&L: ₹{} | Reason: {} | Trades today: {}",
            strategyId, daily.tradeDirection, exitSymbol, exitPrice, daily.entryPremium, pnl, reason, daily.entriesToday);

        daily.exitTrade();
    }

    // ========== Daily State ==========

    private static class DailyState {
        Bias bias = Bias.NEUTRAL;
        Position position = Position.FLAT;
        Direction tradeDirection = null;
        boolean entryLocked = false;
        int entriesToday = 0;
        int candlesSinceEntry = 0;

        // 9:30 / 11:00 snapshots
        double nifty930 = 0;
        double nifty1100 = 0;
        double pcr930 = 0;
        double pcr1100 = 0;
        boolean snapshot930Done = false;
        boolean snapshot1100Done = false;

        // VWAP accumulator
        double cumulativeTpVol = 0;
        double cumulativeVol = 0;
        boolean vwapReady = false;

        // All candles today (for VWAP and tracking)
        final java.util.List<Candle> allCandles = new java.util.ArrayList<>();

        // Trade state
        double entryPremium = 0;
        double entryUnderlying = 0;
        double currentSlPremium = 0;
        java.time.Instant entryTime = null;

        // Live-mode option position state
        String activeOptionSymbol = null;
        int positionQty = 0;
        boolean longPremium = false;   // true when holding a BOUGHT option (CE or PE)
        double lastPremiumLtp = 0;

        void enterTrade(Direction dir, double entryPremium, double entryUnderlying, java.time.Instant time) {
            this.position = Position.IN_TRADE;
            this.tradeDirection = dir;
            this.entryPremium = entryPremium;
            this.entryUnderlying = entryUnderlying;
            this.entryTime = time;
            this.candlesSinceEntry = 0;
            this.entriesToday++;

            if (longPremium || dir == Direction.LONG) {
                // Bought option (or legacy long): stop below entry
                this.currentSlPremium = entryPremium - INITIAL_SL_POINTS;
            } else {
                this.currentSlPremium = entryPremium + INITIAL_SL_POINTS;
            }
        }

        void exitTrade() {
            this.position = Position.FLAT;
            this.tradeDirection = null;
            this.entryPremium = 0;
            this.entryUnderlying = 0;
            this.currentSlPremium = 0;
            this.entryTime = null;
            this.candlesSinceEntry = 0;
            this.activeOptionSymbol = null;
            this.positionQty = 0;
            this.longPremium = false;
            this.lastPremiumLtp = 0;
        }

        void reset() {
            bias = Bias.NEUTRAL;
            position = Position.FLAT;
            tradeDirection = null;
            entryLocked = false;
            entriesToday = 0;
            candlesSinceEntry = 0;
            nifty930 = 0;
            nifty1100 = 0;
            pcr930 = 0;
            pcr1100 = 0;
            snapshot930Done = false;
            snapshot1100Done = false;
            cumulativeTpVol = 0;
            cumulativeVol = 0;
            vwapReady = false;
            allCandles.clear();
            entryPremium = 0;
            entryUnderlying = 0;
            currentSlPremium = 0;
            entryTime = null;
            activeOptionSymbol = null;
            positionQty = 0;
            longPremium = false;
            lastPremiumLtp = 0;
        }
    }

    // ========== Enums ==========

    enum Bias { BULLISH, BEARISH, NEUTRAL }
    enum Position { FLAT, IN_TRADE }
    enum Direction { LONG, SHORT }

    // ========== Public getters for backtest inspection ==========

    public Bias getBias() { return daily.bias; }
    public Position getPosition() { return daily.position; }
    public int getEntriesToday() { return daily.entriesToday; }
    public double getVwap() { return getVwapBeforeCurrentCandle(); }

    /**
     * Sets 9:30 AM snapshot values (for backtest or live scheduling).
     */
    public void setBaseline930(double niftyPrice, double pcr) {
        synchronized (daily) {
            daily.nifty930 = niftyPrice;
            daily.pcr930 = pcr;
            daily.snapshot930Done = true;
            evaluateBiasIfNeeded();
        }
    }

    /**
     * Sets 11:00 AM snapshot values and triggers bias determination.
     */
    public void setBaseline1100(double niftyPrice, double pcr) {
        synchronized (daily) {
            daily.nifty1100 = niftyPrice;
            daily.pcr1100 = pcr;
            daily.snapshot1100Done = true;
            evaluateBiasIfNeeded();
        }
    }

    private void evaluateBiasIfNeeded() {
        if (!daily.snapshot930Done || !daily.snapshot1100Done) return;

        // Bullish: Price up & PCR not declining (allowing small tolerance if flat)
        // Bearish: Price down & PCR not expanding (allowing small tolerance if flat)
        // triggerTolerance adds hysteresis so a few-paise feed gap can't flip the bias.
        if (daily.nifty1100 > daily.nifty930 + triggerTolerance && daily.pcr1100 >= daily.pcr930 - 0.01) {
            daily.bias = Bias.BULLISH;
        } else if (daily.nifty1100 < daily.nifty930 - triggerTolerance && daily.pcr1100 <= daily.pcr930 + 0.01) {
            daily.bias = Bias.BEARISH;
        } else {
            daily.bias = Bias.NEUTRAL;
        }
        log.info("[{}] Bias determined: {} | 930: price={} pcr={} | 1100: price={} pcr={}",
            strategyId, daily.bias, daily.nifty930, daily.pcr930, daily.nifty1100, daily.pcr1100);
    }

    // ========== Live Mode: Baseline Capture ==========

    /**
     * Returns the most recent available candle for the underlying (live first, then
     * historical fallback) so baseline snapshots still compute if the live feed is late.
     */
    private Optional<Candle> lastAvailableCandle() {
        var last = context.getLastCandle(underlyingSymbol, TIMEFRAME);
        if (last.isPresent()) return last;
        List<Candle> hist = context.getHistoricalCandles(underlyingSymbol, TIMEFRAME, 1);
        if (hist != null && !hist.isEmpty()) return Optional.of(hist.get(hist.size() - 1));
        return Optional.empty();
    }

    /**
     * Captures 9:30 AM baseline: Nifty Futures price from last candle + PCR from NSE.
     */
    private void captureBaseline930() {
        if (context == null) return;

        // Get current Nifty Futures price from last candle
        var lastCandle = lastAvailableCandle();
        if (lastCandle.isEmpty()) {
            log.warn("[{}] No candle available for 9:30 baseline (underlying={})", strategyId, underlyingSymbol);
            return;
        }

        double niftyPrice = lastCandle.get().close().doubleValue();
        double pcr = fetchLivePcr();

        setBaseline930(niftyPrice, pcr);
        persistSnapshot("930", niftyPrice, pcr);
        log.info("[{}] 9:30 Baseline captured: price={} pcr={}", strategyId, niftyPrice, pcr);
    }

    /**
     * Captures 11:00 AM bias check: Nifty Futures price + PCR, then determines bias.
     */
    private void captureBiasCheck1100() {
        if (context == null) return;

        var lastCandle = lastAvailableCandle();
        if (lastCandle.isEmpty()) {
            log.warn("[{}] No candle available for 11:00 bias check (underlying={})", strategyId, underlyingSymbol);
            return;
        }

        double niftyPrice = lastCandle.get().close().doubleValue();
        double pcr = fetchLivePcr();

        setBaseline1100(niftyPrice, pcr);
        persistSnapshot("1100", niftyPrice, pcr);
        log.info("[{}] 11:00 Bias Check captured: price={} pcr={} bias={}", strategyId, niftyPrice, pcr, daily.bias);
    }

    /**
     * Reconstructs the 9:30 / 11:00 bias snapshots from historical 5m candles when they were
     * missed because the process started mid-day (those scheduled events already fired before
     * this instance existed). Without this the {@code bias} stays NEUTRAL forever and the
     * strategy never enters. Intended to be dispatched once after the candle history has been
     * backfilled (see {@code StrategyEngine.warmupAllStrategies}).
     * <p>
     * Historical PCR is not available, so the current live PCR is used for both snapshots;
     * the bias decision therefore hinges on the 9:30→11:00 price move (PCR acts as a
     * secondary confirmation only, and equals itself across both snapshots). If the market
     * isn't yet at the relevant time, that snapshot is left for its normal scheduled event.
     */
    private void recoverBaselinesIfNeeded() {
        if (!isLiveMode() || context == null) return;
        if (daily.snapshot930Done && daily.snapshot1100Done) return; // normal flow already handled it

        ZonedDateTime now = ZonedDateTime.now(IST_ZONE);
        LocalTime t = now.toLocalTime();
        if (t.isBefore(LocalTime.of(9, 30))) return; // not at baseline time yet — wait for normal events

        List<Candle> hist = context.getHistoricalCandles(underlyingSymbol, TIMEFRAME, 300);
        if (hist == null || hist.isEmpty()) {
            log.warn("[{}] Baseline recovery skipped — no 5m history available for {}", strategyId, underlyingSymbol);
            return;
        }

        double pcr = fetchLivePcr(); // only used if no DB snapshot exists (historical PCR unavailable)
        LocalDate today = now.toLocalDate();
        String tradeDate = today.format(DATE_FMT);
        Instant t930 = today.atTime(9, 30).atZone(IST_ZONE).toInstant();
        Instant t1100 = today.atTime(11, 0).atZone(IST_ZONE).toInstant();

        // Prefer a previously persisted (real) snapshot; only reconstruct from history as a
        // fallback when this is the very first process of the day and nothing was captured yet.
        if (!daily.snapshot930Done) {
            TradingDbService.VwapBaselineSnapshot stored = loadStoredSnapshot("930", tradeDate);
            if (stored != null) {
                setBaseline930(stored.price(), stored.pcr());
                log.info("[{}] Recovery: rehydrated 9:30 baseline from DB price={} pcr={}",
                    strategyId, stored.price(), stored.pcr());
            } else if (!t.isBefore(LocalTime.of(9, 30))) {
                Candle c930 = findCandleNear(hist, t930);
                if (c930 != null) {
                    double p = c930.close().doubleValue();
                    setBaseline930(p, pcr);
                    persistSnapshot("930", p, pcr);
                    log.info("[{}] Recovery: reconstructed 9:30 baseline from history price={} pcr={}",
                        strategyId, p, pcr);
                } else {
                    log.warn("[{}] Recovery: no 5m candle near 9:30 found for {}", strategyId, underlyingSymbol);
                }
            }
        }

        if (!daily.snapshot1100Done) {
            TradingDbService.VwapBaselineSnapshot stored = loadStoredSnapshot("1100", tradeDate);
            if (stored != null) {
                setBaseline1100(stored.price(), stored.pcr());
                log.info("[{}] Recovery: rehydrated 11:00 bias snapshot from DB price={} pcr={}",
                    strategyId, stored.price(), stored.pcr());
            } else if (!t.isBefore(LocalTime.of(11, 0))) {
                Candle c1100 = findCandleNear(hist, t1100);
                if (c1100 != null) {
                    double p = c1100.close().doubleValue();
                    setBaseline1100(p, pcr);
                    persistSnapshot("1100", p, pcr);
                    log.info("[{}] Recovery: reconstructed 11:00 bias snapshot from history price={} pcr={}",
                        strategyId, p, pcr);
                } else {
                    log.warn("[{}] Recovery: no 5m candle near 11:00 found for {}", strategyId, underlyingSymbol);
                }
            }
        }
    }

    /** Persists a baseline snapshot to the DB (best-effort, fire-and-forget). No-op if DB absent. */
    private void persistSnapshot(String type, double price, double pcr) {
        if (tradingDbService == null) return;
        try {
            tradingDbService.saveVwapSnapshot(new TradingDbService.VwapBaselineSnapshot(
                strategyId, type, LocalDate.now(IST_ZONE).format(DATE_FMT), price, pcr, Instant.now())).subscribe();
        } catch (Exception e) {
            log.warn("[{}] Failed to persist {} snapshot: {}", strategyId, type, e.getMessage());
        }
    }

    /** Loads a persisted baseline snapshot for today, or null if absent / DB unavailable. */
    private TradingDbService.VwapBaselineSnapshot loadStoredSnapshot(String type, String tradeDate) {
        if (tradingDbService == null) return null;
        try {
            return tradingDbService.loadVwapSnapshot(strategyId, type, tradeDate)
                .block().orElse(null);
        } catch (Exception e) {
            log.warn("[{}] Failed to load stored {} snapshot: {}", strategyId, type, e.getMessage());
            return null;
        }
    }

    /** Returns the candle in the list whose timestamp is closest to the given target instant. */
    private static Candle findCandleNear(List<Candle> candles, Instant target) {
        Candle best = null;
        long bestDelta = Long.MAX_VALUE;
        for (Candle c : candles) {
            if (c.timestamp() == null) continue;
            long delta = Math.abs(c.timestamp().getEpochSecond() - target.getEpochSecond());
            if (delta < bestDelta) {
                bestDelta = delta;
                best = c;
            }
        }
        return best;
    }

    /**
     * Fetches real-time PCR from Kite/Zerodha quotes API.
     * Falls back to default 1.0 if Kite is unavailable.
     */
    private double fetchLivePcr() {
        if (kitePcrProvider == null) {
            log.debug("[{}] No KitePcrProvider available, using default PCR 1.0", strategyId);
            return 1.0;
        }

        try {
            // Get current Nifty price from last candle for ATM strike calculation
            double spotPrice = 24000.0;
            if (context != null) {
                var lastCandle = context.getLastCandle(underlyingSymbol, TIMEFRAME);
                if (lastCandle.isPresent()) {
                    spotPrice = lastCandle.get().close().doubleValue();
                }
            }
            return kitePcrProvider.fetchPcr(spotPrice);
        } catch (Exception e) {
            log.warn("[{}] Failed to fetch Kite PCR, using default 1.0: {}", strategyId, e.getMessage());
            return 1.0;
        }
    }
}
