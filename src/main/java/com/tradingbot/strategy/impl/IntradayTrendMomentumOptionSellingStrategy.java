package com.tradingbot.strategy.impl;

import com.tradingbot.backtest.BlackScholesPricer;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.instrument.LotSizeService;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.KitePcrProvider;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import com.tradingbot.strategy.TechnicalIndicators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Intraday Trend & Momentum Option Selling Strategy.
 *
 * Combines SuperTrend (15m) with RSI (1h) to identify directional bias,
 * then sells OTM options (PE for bullish, CE for bearish) with delta ≈ 0.20.
 * Includes hedge leg for margin relief, 30% stop-loss, and optional re-entry.
 *
 * @see <a href="st_intraday_option_selling.md">Strategy Specification</a>
 */
@Component
public class IntradayTrendMomentumOptionSellingStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(IntradayTrendMomentumOptionSellingStrategy.class);
    private static final String TIMEFRAME_15M = "15";
    private static final String TIMEFRAME_1H = "60";
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String strategyId;
    private final String assignedAccountId;
    private final String symbol;
    private final int superTrendAtrLength;
    private final double superTrendMultiplier;
    private final int rsiPeriod;
    private final double rsiThreshold;
    private final double rsiUpperThreshold;
    private final double rsiLowerThreshold;
    private final double targetDelta;
    private final double minPremium;
    private final double stopLossPct;
    private final double profitTargetPct;
    private final int lots;
    private final String eodExitTime;
    private final LocalTime parsedEodExitTime;
    private final int reEntryCooldownCandles;
    private final int maxTradesPerDay;
    private final List<String> blackoutDays;

    private final KitePcrProvider kitePcrProvider;
    private final InstrumentMasterService instrumentMaster;
    private final LotSizeService lotSizeService;
    private final CandleAggregator candleAggregator;

    private final List<String> symbols = new CopyOnWriteArrayList<>();
    private volatile String underlyingSymbol;

    private StrategyContext context;
    private volatile boolean enabled = true;

    private final DailyState daily = new DailyState();

    private volatile String lastEntryOutcome = "NONE";
    private volatile String lastEntryDetail = "";

    @Autowired
    public IntradayTrendMomentumOptionSellingStrategy(
        @Value("${st-intraday.strategy-id:ST_INTRADAY_01}") String strategyId,
        @Value("${st-intraday.account-id:KITE_USER_01}") String assignedAccountId,
        @Value("${st-intraday.symbol:NFO:NIFTY_50}") String symbol,
        @Value("${st-intraday.super-trend.atr-length:7}") int superTrendAtrLength,
        @Value("${st-intraday.super-trend.multiplier:3.0}") double superTrendMultiplier,
        @Value("${st-intraday.rsi.period:14}") int rsiPeriod,
        @Value("${st-intraday.rsi.threshold:50.0}") double rsiThreshold,
        @Value("${st-intraday.rsi.upper-threshold:55.0}") double rsiUpperThreshold,
        @Value("${st-intraday.rsi.lower-threshold:45.0}") double rsiLowerThreshold,
        @Value("${st-intraday.option-selection.target-delta:0.20}") double targetDelta,
        @Value("${st-intraday.option-selection.min-premium:70.0}") double minPremium,
        @Value("${st-intraday.risk.stop-loss-pct:60.0}") double stopLossPct,
        @Value("${st-intraday.risk.profit-target-pct:50.0}") double profitTargetPct,
        @Value("${st-intraday.lots:1}") int lots,
        @Value("${st-intraday.eod-exit-time:15:00}") String eodExitTime,
        @Value("${st-intraday.re-entry-cooldown-candles:3}") int reEntryCooldownCandles,
        @Value("${st-intraday.risk.max-trades-per-day:${st-intraday.max-trades-per-day:0}}") int maxTradesPerDay,
        @Value("${st-intraday.blackout-days:}") List<String> blackoutDays,
        KitePcrProvider kitePcrProvider,
        InstrumentMasterService instrumentMaster,
        LotSizeService lotSizeService,
        CandleAggregator candleAggregator
    ) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.symbol = symbol;
        this.superTrendAtrLength = superTrendAtrLength;
        this.superTrendMultiplier = superTrendMultiplier;
        this.rsiPeriod = rsiPeriod;
        this.rsiThreshold = rsiThreshold;
        this.rsiUpperThreshold = rsiUpperThreshold;
        this.rsiLowerThreshold = rsiLowerThreshold;
        this.targetDelta = targetDelta;
        this.minPremium = minPremium;
        this.stopLossPct = stopLossPct;
        this.profitTargetPct = profitTargetPct;
        this.lots = lots;
        this.eodExitTime = eodExitTime;
        this.parsedEodExitTime = parseEodExitTime(eodExitTime);
        this.reEntryCooldownCandles = reEntryCooldownCandles;
        this.maxTradesPerDay = maxTradesPerDay;
        this.blackoutDays = blackoutDays != null ? blackoutDays : List.of();
        this.kitePcrProvider = kitePcrProvider;
        this.instrumentMaster = instrumentMaster;
        this.lotSizeService = lotSizeService;
        this.candleAggregator = candleAggregator;
        this.underlyingSymbol = symbol;
        this.symbols.add(symbol);
    }

    public IntradayTrendMomentumOptionSellingStrategy(
        String strategyId,
        String assignedAccountId,
        String symbol,
        int superTrendAtrLength,
        double superTrendMultiplier,
        int rsiPeriod,
        double rsiThreshold,
        double targetDelta,
        double minPremium,
        double stopLossPct,
        double profitTargetPct,
        int lots,
        String eodExitTime,
        int reEntryCooldownCandles,
        int maxTradesPerDay,
        List<String> blackoutDays,
        KitePcrProvider kitePcrProvider,
        InstrumentMasterService instrumentMaster,
        LotSizeService lotSizeService,
        CandleAggregator candleAggregator
    ) {
        this(strategyId, assignedAccountId, symbol, superTrendAtrLength, superTrendMultiplier,
            rsiPeriod, rsiThreshold, rsiThreshold + 5.0, rsiThreshold - 5.0,
            targetDelta, minPremium, stopLossPct, profitTargetPct, lots, eodExitTime,
            reEntryCooldownCandles, maxTradesPerDay, blackoutDays,
            kitePcrProvider, instrumentMaster, lotSizeService, candleAggregator);
    }

    public IntradayTrendMomentumOptionSellingStrategy(String strategyId, String assignedAccountId, String symbol) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.symbol = symbol;
        this.superTrendAtrLength = 7;
        this.superTrendMultiplier = 3.0;
        this.rsiPeriod = 14;
        this.rsiThreshold = 50.0;
        this.rsiUpperThreshold = 55.0;
        this.rsiLowerThreshold = 45.0;
        this.targetDelta = 0.20;
        this.minPremium = 70.0;
        this.stopLossPct = 60.0;
        this.profitTargetPct = 50.0;
        this.lots = 1;
        this.eodExitTime = "15:00";
        this.parsedEodExitTime = parseEodExitTime(eodExitTime);
        this.reEntryCooldownCandles = 3;
        this.maxTradesPerDay = 0;
        this.blackoutDays = List.of();
        this.kitePcrProvider = null;
        this.instrumentMaster = null;
        this.lotSizeService = null;
        this.candleAggregator = null;
        this.underlyingSymbol = symbol;
        this.symbols.add(symbol);
    }

    private static LocalTime parseEodExitTime(String timeStr) {
        try {
            if (timeStr != null && timeStr.contains(":")) {
                String[] parts = timeStr.trim().split(":");
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
        } catch (Exception ignored) {}
        return LocalTime.of(15, 20);
    }

    private boolean isLiveMode() {
        return instrumentMaster != null && kitePcrProvider != null;
    }

    @Override
    public String getStrategyId() { return strategyId; }

    @Override
    public String getAssignedAccountId() { return assignedAccountId; }

    @Override
    public List<String> getSubscribedSymbols() { return Collections.unmodifiableList(symbols); }

    @Override
    public void init(StrategyContext context) {
        this.context = context;
    }

    @Override
    public void onTick(Tick tick) {
        if (!enabled || tick == null) return;
        synchronized (daily) {
            String premiumSymbol = daily.activeShortSymbol != null ? daily.activeShortSymbol : symbol;
            // In backtest mode, tick comes from underlying symbol
            String tickSymbol = tick.symbol();
            if (!premiumSymbol.equals(tickSymbol) && !symbol.equals(tickSymbol)) return;

            if (daily.activeShortSymbol != null && daily.activeShortSymbol.equals(tickSymbol)) {
                daily.lastPremiumLtp = tick.ltp().doubleValue();
            }

            // In backtest mode, simulate option price movement based on underlying
            if (!isLiveMode() && daily.lastTickPrice > 0 && daily.tradeDirection != null) {
                double tickPrice = tick.ltp().doubleValue();
                double priceChange = tickPrice - daily.lastTickPrice;
                if (daily.tradeDirection == Direction.BULLISH) {
                    // Selling PE: underlying up = PE premium down (profit)
                    daily.currentPremium = Math.max(0.1, daily.currentPremium - priceChange * 0.3);
                } else if (daily.tradeDirection == Direction.BEARISH) {
                    // Selling CE: underlying down = CE premium down (profit)
                    daily.currentPremium = Math.max(0.1, daily.currentPremium + priceChange * 0.3);
                }
                daily.lastTickPrice = tickPrice;
            }

            if (daily.position == Position.IN_TRADE) {
                checkTickExits(tick);
            }
        }
    }

    @Override
    public void onCandle(Candle candle) {
        if (!enabled || candle == null) return;
        if (!underlyingSymbol.equals(candle.symbol())) return;

        // Auto-reset on new trading day (needed for backtest where onSchedule is not called)
        ZonedDateTime candleTime = candle.timestamp().atZone(IST_ZONE);
        String candleDate = candleTime.format(DATE_FMT);
        if (!candleDate.equals(daily.currentDate)) {
            if (daily.position == Position.IN_TRADE) {
                squareOffAll("EOD_SQUARE_OFF");
            }
            daily.currentDate = candleDate;
            synchronized (daily) {
                daily.reset();
            }
            log.info("[{}] New day detected: {}", strategyId, candleDate);
        }

        // Process 60m candles for RSI update
        if (TIMEFRAME_1H.equals(candle.timeframe())) {
            log.debug("[{}] 1h candle closed at {}, updating RSI", strategyId, candle.timestamp());
            return;
        }

        // Process 15m candles for SuperTrend and entry/exit evaluation
        if (!TIMEFRAME_15M.equals(candle.timeframe())) return;

        synchronized (daily) {
            if (daily.position == Position.WAIT_FOR_REENTRY) {
                daily.candlesSinceExit++;
                checkReEntry(candle);
            } else if (daily.position == Position.IN_TRADE) {
                daily.candlesSinceEntry++;
                checkCandleExits(candle);
            } else if (daily.position == Position.FLAT
                && !daily.entryLocked
                && !isBlackoutDay()) {
                evaluateEntry(candle);
            }
        }
    }

    @Override
    public void onSchedule(ScheduledEvent event) {
        if (!enabled || event == null) return;
        switch (event.eventType()) {
            case ScheduledEvent.PRE_MARKET_SCAN -> {
                synchronized (daily) {
                    daily.reset();
                }
                resolveUnderlying();
            }
            case ScheduledEvent.MARKET_OPEN -> { }
            case ScheduledEvent.INTRADAY_ENTRY_CUTOFF -> {
                synchronized (daily) {
                    daily.entryLocked = true;
                }
            }
            case ScheduledEvent.INTRADAY_SQUARE_OFF -> {
                squareOffAll("EOD_SQUARE_OFF");
                synchronized (daily) {
                    daily.entryLocked = true;
                }
            }
            case ScheduledEvent.MARKET_CLOSE -> {
                synchronized (daily) {
                    daily.reset();
                }
            }
        }
    }

    private void resolveUnderlying() {
        if (instrumentMaster == null) return;
        try {
            var fut = instrumentMaster.findNearestExpiring("NIFTY", "FUT").blockOptional();
            if (fut.isPresent()) {
                underlyingSymbol = fut.get().canonicalSymbol();
                symbols.clear();
                symbols.add(underlyingSymbol);
                if (context != null) context.requestSubscriptionSync();
                log.info("[{}] Resolved underlying: {}", strategyId, underlyingSymbol);
            }
        } catch (Exception e) {
            log.warn("[{}] Failed to resolve underlying: {}", strategyId, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        synchronized (daily) {
            daily.reset();
        }
    }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // ========== Entry Logic ==========

    private void evaluateEntry(Candle candle) {
        if (context == null) return;

        // Get 15m candles - single source for all OHLC arrays (chronological order)
        List<Candle> candles15m = context.getHistoricalCandles(underlyingSymbol, TIMEFRAME_15M, 100);
        if (candles15m == null || candles15m.size() < superTrendAtrLength + 2) {
            return;
        }

        int len15m = candles15m.size();
        double[] highs15m = new double[len15m];
        double[] lows15m = new double[len15m];
        double[] closes15m = new double[len15m];
        for (int i = 0; i < len15m; i++) {
            Candle c = candles15m.get(i);
            highs15m[i] = c.high().doubleValue();
            lows15m[i] = c.low().doubleValue();
            closes15m[i] = c.close().doubleValue();
        }

        // Get 1h candles for RSI
        double[] closes1h = context.getClosePrices(underlyingSymbol, TIMEFRAME_1H);
        if (closes1h == null || closes1h.length < rsiPeriod + 2) {
            return;
        }

        // Calculate SuperTrend on 15m
        double[] superTrend = TechnicalIndicators.calculateSuperTrend(highs15m, lows15m, closes15m,
            superTrendAtrLength, superTrendMultiplier);
        double lastSuperTrend = superTrend[superTrend.length - 1];
        if (Double.isNaN(lastSuperTrend)) return;

        double close15m = closes15m[closes15m.length - 1];
        boolean bullishSuperTrend = lastSuperTrend > 0;
        boolean bearishSuperTrend = lastSuperTrend < 0;

        // Calculate RSI on 1h with deadband (>= 55 for Bullish, <= 45 for Bearish)
        double rsi1h = TechnicalIndicators.calculateRsi(closes1h, rsiPeriod);
        if (Double.isNaN(rsi1h)) return;

        boolean bullishRsi = rsi1h >= rsiUpperThreshold;
        boolean bearishRsi = rsi1h <= rsiLowerThreshold;

        // Determine direction
        Direction direction = null;
        if (bullishSuperTrend && bullishRsi) {
            direction = Direction.BULLISH;
        } else if (bearishSuperTrend && bearishRsi) {
            direction = Direction.BEARISH;
        }

        if (direction == null) {
            return;
        }

        log.debug("[{}] Signal detected: {} | ST: {} (level: {}), RSI: {}",
            strategyId, direction, bullishSuperTrend ? "BULLISH" : "BEARISH",
            Math.abs(lastSuperTrend), rsi1h);

        // Note: re-entry after a stop-out is handled exclusively by checkReEntry()
        // (Position.WAIT_FOR_REENTRY), which enforces its own cooldown + direction
        // re-validation. evaluateEntry only runs when Position.FLAT, so it must not
        // re-apply the re-entry guard here.

        // Check daily max trades limit (0 or negative means unlimited)
        if (maxTradesPerDay > 0 && daily.entriesToday >= maxTradesPerDay) {
            log.debug("[{}] Daily trade limit reached ({}/{}), skipping entry",
                strategyId, daily.entriesToday, maxTradesPerDay);
            return;
        }

        // Check if already in a trade
        if (daily.position != Position.FLAT) {
            log.debug("[{}] Already in position: {}, skipping entry", strategyId, daily.position);
            return;
        }

        log.info("[{}] Signal: {} | SuperTrend: {} (level: {}), RSI_1H: {}",
            strategyId, direction, bullishSuperTrend ? "BULLISH" : "BEARISH",
            Math.abs(lastSuperTrend), rsi1h);

        if (isLiveMode()) {
            enterLiveTrade(direction, close15m, candle.timestamp());
        } else {
            // Backtest mode: use underlying price as proxy for option premium
            // In real trading, this would be the actual option premium
            double estimatedPremium = close15m * 0.02; // ~2% of spot as rough OTM premium estimate
            daily.entryPremium = estimatedPremium;
            daily.slPrice = estimatedPremium * (1.0 + stopLossPct / 100.0);
            daily.tradeDirection = direction;
            daily.position = Position.IN_TRADE;
            daily.entriesToday++;
            daily.positionQty = lotSizeService != null ? lotSizeService.getOrderQuantity("NIFTY") : 25 * lots;
            daily.entryTime = candle.timestamp();
            daily.activeShortSymbol = symbol + "_" + direction;
            daily.currentPremium = estimatedPremium;
            daily.lastTickPrice = close15m;

            String optionType = direction == Direction.BULLISH ? "PE" : "CE";
            String tag = "ST_" + direction + "_ENTRY";
            Signal entrySignal = Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(symbol)
                .signalType(SignalType.ENTRY_SHORT)
                .quantity(daily.positionQty)
                .price(BigDecimal.valueOf(estimatedPremium))
                .orderType(OrderType.MARKET)
                .productType(ProductType.MIS)
                .tag(tag)
                .timestamp(candle.timestamp())
                .build();
            context.emitSignal(entrySignal);

            log.info("[{}] BACKTEST ENTRY: {} @ ₹{} (est) | SL: ₹{} | Direction: {}",
                strategyId, symbol, estimatedPremium, daily.slPrice, direction);
        }
    }

    private double round2(double v) {
        if (Double.isNaN(v)) return v;
        return Math.round(v * 100.0) / 100.0;
    }

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void logDiagnostic() {
        if (!enabled || context == null) return;
        try {
            String sym = underlyingSymbol;
            List<Candle> c15 = context.getHistoricalCandles(sym, TIMEFRAME_15M, 100);
            double[] closes1h = context.getClosePrices(sym, TIMEFRAME_1H);
            int n15 = c15 == null ? 0 : c15.size();
            int n1h = closes1h == null ? 0 : closes1h.length;

            double stLast = Double.NaN;
            boolean bullST = false, bearST = false;
            if (c15 != null && c15.size() >= superTrendAtrLength + 2) {
                double[] hi = c15.stream().mapToDouble(c -> c.high().doubleValue()).toArray();
                double[] lo = c15.stream().mapToDouble(c -> c.low().doubleValue()).toArray();
                double[] cl = c15.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] st = TechnicalIndicators.calculateSuperTrend(hi, lo, cl, superTrendAtrLength, superTrendMultiplier);
                stLast = st[st.length - 1];
                bullST = stLast > 0; bearST = stLast < 0;
            }
            double rsi = (closes1h != null && closes1h.length >= rsiPeriod + 2)
                ? TechnicalIndicators.calculateRsi(closes1h, rsiPeriod) : Double.NaN;
            boolean bullRsi = !Double.isNaN(rsi) && rsi >= rsiUpperThreshold;
            boolean bearRsi = !Double.isNaN(rsi) && rsi <= rsiLowerThreshold;

            String gate;
            if (n15 < superTrendAtrLength + 2) gate = "INSUFFICIENT_15M(" + n15 + ")";
            else if (n1h < rsiPeriod + 2) gate = "INSUFFICIENT_1H(" + n1h + ")";
            else if (Double.isNaN(stLast)) gate = "ST_NAN";
            else if (Double.isNaN(rsi)) gate = "RSI_NAN";
            else if (daily.position != Position.FLAT) gate = "IN_TRADE(" + daily.position + ")";
            else if (daily.entryLocked) gate = "ENTRY_LOCKED";
            else if (isBlackoutDay()) gate = "BLACKOUT";
            else if (bullST && bullRsi) gate = "WOULD_ENTRY_BULLISH";
            else if (bearST && bearRsi) gate = "WOULD_ENTRY_BEARISH";
            else gate = "NO_DIRECTION(ST=" + (bearST ? "BEAR" : (bullST ? "BULL" : "n/a"))
                + ",RSI=" + round2(rsi) + ",needRsi<=" + rsiLowerThreshold + "or>= " + rsiUpperThreshold + ")";

            log.info("[{}] DIAG | sym={} | 15m={} | 1h={} | ST={}({}) | RSI1h={} | gate={} | pos={} | entries={} | locked={} | lastEntry={}:{}",
                strategyId, sym, n15, n1h, bearST ? "BEAR" : (bullST ? "BULL" : "n/a"),
                round2(Math.abs(stLast)), round2(rsi), gate, daily.position, daily.entriesToday,
                daily.entryLocked, lastEntryOutcome, lastEntryDetail);
        } catch (Exception e) {
            log.warn("[{}] DIAG failed: {}", strategyId, e.getMessage());
        }
    }

    private record SelectedOptionTrade(
        Instrument shortOption,
        double shortPremium,
        String expiry,
        Optional<Instrument> hedgeOption,
        double hedgePremium
    ) {}

    private void enterLiveTrade(Direction direction, double spotPrice, Instant time) {
        String optionType = direction == Direction.BULLISH ? "PE" : "CE";

        try {
            // Select 0.20 delta option: if nearest expiry premium < minPremium (Rs 70), check next expiry
            var tradeSelection = selectOptionForTrading(spotPrice, optionType);
            if (tradeSelection.isEmpty()) {
                lastEntryOutcome = "NO_CANDIDATE";
                lastEntryDetail = optionType + " spot=" + round2(spotPrice) + " (selectOptionForTrading returned empty)";
                log.warn("[{}] No {} option candidate found near spot {}", strategyId, optionType, spotPrice);
                return;
            }

            SelectedOptionTrade selected = tradeSelection.get();
            Instrument shortOpt = selected.shortOption();
            String optionSymbol = shortOpt.canonicalSymbol();
            double premium = selected.shortPremium();

            int qty = lotSizeService != null ? lotSizeService.getOrderQuantity("NIFTY") : 25 * lots;

            // Subscribe to short option symbol for tick data
            if (!symbols.contains(optionSymbol)) {
                symbols.add(optionSymbol);
                if (context != null) context.requestSubscriptionSync();
            }

            // Calculate SL price
            double slPrice = premium * (1.0 + stopLossPct / 100.0);

            // Update daily state
            daily.activeShortSymbol = optionSymbol;
            daily.positionQty = qty;
            daily.lastPremiumLtp = premium;
            daily.tradeDirection = direction;
            daily.entryPremium = premium;
            daily.slPrice = slPrice;
            daily.entryTime = time;
            daily.position = Position.IN_TRADE;
            daily.entriesToday++;

            // Emit entry signal for short leg (dispatches alert to Telegram)
            String tag = "ST_" + optionType + "_SHORT";
            Signal entrySignal = Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(optionSymbol)
                .signalType(SignalType.ENTRY_SHORT)
                .quantity(qty)
                .price(BigDecimal.valueOf(premium))
                .orderType(OrderType.LIMIT)
                .productType(ProductType.MIS)
                .protectiveStopTrigger(BigDecimal.valueOf(slPrice))
                .tag(tag)
                .timestamp(time)
                .build();
            context.emitSignal(entrySignal);
            lastEntryOutcome = "SIGNAL_EMITTED";
            lastEntryDetail = optionSymbol + " @ ₹" + round2(premium) + " (" + optionType + ")";

            // Emit hedge leg if available
            if (selected.hedgeOption().isPresent() && selected.hedgePremium() > 0) {
                Instrument hedgeOpt = selected.hedgeOption().get();
                String hedgeSymbol = hedgeOpt.canonicalSymbol();
                if (!hedgeSymbol.equals(optionSymbol)) {
                    daily.activeHedgeSymbol = hedgeSymbol;
                    daily.hedgePremium = selected.hedgePremium();

                    if (!symbols.contains(hedgeSymbol)) {
                        symbols.add(hedgeSymbol);
                        if (context != null) context.requestSubscriptionSync();
                    }

                    String hedgeTag = "ST_" + optionType + "_HEDGE";
                    Signal hedgeSignal = Signal.builder()
                        .strategyId(strategyId)
                        .targetAccountId(assignedAccountId)
                        .symbol(hedgeSymbol)
                        .signalType(SignalType.ENTRY_LONG)
                        .quantity(qty)
                        .price(BigDecimal.valueOf(selected.hedgePremium()))
                        .orderType(OrderType.LIMIT)
                        .productType(ProductType.MIS)
                        .tag(hedgeTag)
                        .timestamp(time)
                        .build();
                    context.emitSignal(hedgeSignal);
                }
            }

            log.info("[{}] LIVE ENTRY: {} x {} @ ₹{} (Expiry: {}) | SL: ₹{} | Hedge: {} @ ₹{} | Direction: {}",
                strategyId, optionSymbol, qty, premium, selected.expiry(), slPrice, daily.activeHedgeSymbol, daily.hedgePremium, direction);

        } catch (Exception e) {
            log.error("[{}] Live entry failed: {}", strategyId, e.getMessage(), e);
        }
    }

    /**
     * Selects the OTM option for trading based on delta ~0.20:
     * 1. Checks the nearest expiry: if its 0.20 delta premium >= minPremium (Rs 70), selects it.
     * 2. If nearest expiry premium < minPremium (Rs 70), checks the next expiry 0.20 delta premium and selects it.
     */
    private Optional<SelectedOptionTrade> selectOptionForTrading(double spotPrice, String optionType) {
        if (instrumentMaster == null) return Optional.empty();

        try {
            List<String> expiries = instrumentMaster.findUpcomingExpiries("NIFTY", optionType, 3)
                .collectList()
                .block();
            log.info("[{}] selectOptionForTrading: kitePcrProvider={}, expiries={}", strategyId,
                kitePcrProvider != null, expiries);

            if (expiries == null || expiries.isEmpty()) {
                var nearestOpt = instrumentMaster.findNearestExpiring("NIFTY", optionType).blockOptional();
                if (nearestOpt.isPresent() && nearestOpt.get().expiry() != null) {
                    expiries = List.of(nearestOpt.get().expiry());
                } else {
                    lastEntryOutcome = "NO_EXPIRIES";
                    lastEntryDetail = "findUpcomingExpiries & findNearestExpiring empty for " + optionType;
                    log.warn("[{}] No expiries found for NIFTY {} — cannot select option", strategyId, optionType);
                    return Optional.empty();
                }
            }

            Instrument chosenOption = null;
            double chosenPremium = 0.0;
            String chosenExpiry = null;

            for (int i = 0; i < expiries.size(); i++) {
                String expiry = expiries.get(i);
                var candidateOpt = findOtmOptionForExpiry(spotPrice, optionType, targetDelta, expiry);
                if (candidateOpt.isEmpty()) continue;

                String sym = candidateOpt.get().canonicalSymbol();
                double ltp = kitePcrProvider != null ? kitePcrProvider.fetchLtp(sym) : 0.0;
                if (ltp <= 0) {
                    try {
                        double tte = calculateTimeToExpiryYears(expiry);
                        double theo = "CE".equalsIgnoreCase(optionType)
                            ? BlackScholesPricer.callPrice(spotPrice, candidateOpt.get().strike().doubleValue(), tte, 0.07, 0.18)
                            : BlackScholesPricer.putPrice(spotPrice, candidateOpt.get().strike().doubleValue(), tte, 0.07, 0.18);
                        if (theo > 0) {
                            log.warn("[{}] LTP unavailable for {} (kitePcrProvider={}); using theoretical premium ₹{}",
                                strategyId, sym, kitePcrProvider != null, round2(theo));
                            ltp = theo;
                        } else {
                            log.warn("[{}] LTP & theoretical premium unavailable for {}; skipping", strategyId, sym);
                            continue;
                        }
                    } catch (Exception ex) {
                        log.warn("[{}] LTP unavailable and theoretical premium failed for {}: {}", strategyId, sym, ex.getMessage());
                        continue;
                    }
                }

                if (i == 0) {
                    // Nearest expiry check
                    if (minPremium <= 0 || ltp >= minPremium) {
                        log.info("[{}] Selected nearest expiry {} 0.20Δ option {} @ ₹{} (>= ₹{})",
                            strategyId, expiry, sym, ltp, minPremium);
                        chosenOption = candidateOpt.get();
                        chosenPremium = ltp;
                        chosenExpiry = expiry;
                        break;
                    } else {
                        log.info("[{}] Nearest expiry {} 0.20Δ option {} premium ₹{} < ₹{} threshold — checking next expiry",
                            strategyId, expiry, sym, ltp, minPremium);
                        // Save as fallback if no next expiry exists
                        chosenOption = candidateOpt.get();
                        chosenPremium = ltp;
                        chosenExpiry = expiry;
                    }
                } else {
                    // Next expiry check
                    log.info("[{}] Selected next expiry {} 0.20Δ option {} @ ₹{}",
                        strategyId, expiry, sym, ltp);
                    chosenOption = candidateOpt.get();
                    chosenPremium = ltp;
                    chosenExpiry = expiry;
                    break;
                }
            }

            if (chosenOption == null || chosenPremium <= 0) {
                lastEntryOutcome = "NO_CANDIDATE_PREMIUM";
                lastEntryDetail = "all expiries skipped (LTP unavailable or premium<=0)";
                log.warn("[{}] selectOptionForTrading: no valid option after scanning {} expiries", strategyId, expiries.size());
                return Optional.empty();
            }

            // Find hedge option (~0.05 delta) on the same chosen expiry
            Optional<Instrument> hedgeOpt = findOtmOptionForExpiry(spotPrice, optionType, 0.05, chosenExpiry);
            double hedgeLtp = 0.0;
            if (hedgeOpt.isPresent()) {
                String hedgeSym = hedgeOpt.get().canonicalSymbol();
                if (!hedgeSym.equals(chosenOption.canonicalSymbol()) && kitePcrProvider != null) {
                    hedgeLtp = kitePcrProvider.fetchLtp(hedgeSym);
                }
            }

            return Optional.of(new SelectedOptionTrade(chosenOption, chosenPremium, chosenExpiry, hedgeOpt, hedgeLtp));

        } catch (Exception e) {
            log.error("[{}] Error selecting option for trading: {}", strategyId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private Optional<Instrument> findOtmOption(
            double spotPrice, String optionType, double targetDelta) {
        if (instrumentMaster == null) return Optional.empty();
        try {
            var nearestOpt = instrumentMaster.findNearestExpiring("NIFTY", optionType).blockOptional();
            if (nearestOpt.isEmpty()) return Optional.empty();
            return findOtmOptionForExpiry(spotPrice, optionType, targetDelta, nearestOpt.get().expiry());
        } catch (Exception e) {
            log.warn("[{}] Failed to find nearest OTM option: {}", strategyId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Instrument> findOtmOptionForExpiry(
            double spotPrice, String optionType, double targetDelta, String expiry) {
        if (instrumentMaster == null || expiry == null) return Optional.empty();

        try {
            List<Instrument> options = instrumentMaster.findOptionContracts("NIFTY", expiry, null, optionType)
                .collectList()
                .block();

            if (options == null || options.isEmpty()) {
                return Optional.empty();
            }

            double timeToExpiry = calculateTimeToExpiryYears(expiry);
            double r = 0.07;
            double vol = 0.15;

            return options.stream()
                .filter(opt -> opt.strike() != null)
                .min(Comparator.comparingDouble(opt -> {
                    double strike = opt.strike().doubleValue();
                    double delta = "CE".equalsIgnoreCase(optionType)
                        ? BlackScholesPricer.callDelta(spotPrice, strike, timeToExpiry, r, vol)
                        : Math.abs(BlackScholesPricer.putDelta(spotPrice, strike, timeToExpiry, r, vol));
                    return Math.abs(delta - targetDelta);
                }));
        } catch (Exception e) {
            log.warn("[{}] Failed to find OTM option for expiry {}: {}", strategyId, expiry, e.getMessage());
            return Optional.empty();
        }
    }
    private double calculateTimeToExpiryYears(String expiryStr) {
        if (expiryStr == null || expiryStr.isEmpty()) return 7.0 / 365.0;
        try {
            LocalDate expiry = LocalDate.parse(expiryStr);
            LocalDate today = context != null
                ? context.now().atZone(IST_ZONE).toLocalDate()
                : LocalDate.now(IST_ZONE);
            long days = ChronoUnit.DAYS.between(today, expiry);
            return Math.max(days, 0.5) / 365.0;
        } catch (Exception e) {
            return 7.0 / 365.0;
        }
    }

    // ========== Exit Logic ==========

    private void checkCandleExits(Candle candle) {
        // 1. Cut position immediately if 15m SuperTrend flips against active trade
        if (context != null && daily.tradeDirection != null) {
            List<Candle> candles15m = context.getHistoricalCandles(underlyingSymbol, TIMEFRAME_15M, 100);
            if (candles15m != null && candles15m.size() >= superTrendAtrLength + 2) {
                int len = candles15m.size();
                double[] highs = new double[len];
                double[] lows = new double[len];
                double[] closes = new double[len];
                for (int i = 0; i < len; i++) {
                    highs[i] = candles15m.get(i).high().doubleValue();
                    lows[i] = candles15m.get(i).low().doubleValue();
                    closes[i] = candles15m.get(i).close().doubleValue();
                }
                double[] st = TechnicalIndicators.calculateSuperTrend(highs, lows, closes, superTrendAtrLength, superTrendMultiplier);
                double lastSt = st[st.length - 1];
                if (!Double.isNaN(lastSt)) {
                    if (daily.tradeDirection == Direction.BULLISH && lastSt < 0) {
                        double currentPremium = isLiveMode()
                            ? (daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.entryPremium)
                            : daily.currentPremium;
                        exitTrade(currentPremium, "SUPERTREND_FLIP_EXIT", candle.timestamp());
                        return;
                    } else if (daily.tradeDirection == Direction.BEARISH && lastSt > 0) {
                        double currentPremium = isLiveMode()
                            ? (daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.entryPremium)
                            : daily.currentPremium;
                        exitTrade(currentPremium, "SUPERTREND_FLIP_EXIT", candle.timestamp());
                        return;
                    }
                }
            }
        }

        // 2. Check EOD exit
        if (isEodTime()) {
            double currentPremium = isLiveMode()
                ? (daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.entryPremium)
                : daily.currentPremium;
            exitTrade(currentPremium, "EOD_EXIT", candle.timestamp());
        }
    }

    private void checkTickExits(Tick tick) {
        Instant now = tick.timestamp() != null ? tick.timestamp() : Instant.now();
        double currentPremium = isLiveMode()
            ? (daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.entryPremium)
            : daily.currentPremium;

        // Check stop loss
        if (currentPremium >= daily.slPrice) {
            exitTrade(currentPremium, "SL_HIT", now);
            return;
        }

        // Check profit target (if enabled)
        if (profitTargetPct > 0) {
            double profitTarget = daily.entryPremium * (1.0 - profitTargetPct / 100.0);
            if (currentPremium <= profitTarget) {
                exitTrade(currentPremium, "PROFIT_TARGET", now);
                return;
            }
        }

        // Check EOD
        if (isEodTime()) {
            exitTrade(currentPremium, "EOD_EXIT", now);
        }
    }

    private void checkReEntry(Candle candle) {
        if (daily.position != Position.WAIT_FOR_REENTRY) return;
        if (daily.candlesSinceExit < reEntryCooldownCandles) return;

        // Get 15m candles - single source for all OHLC arrays
        List<Candle> candles15m = context.getHistoricalCandles(underlyingSymbol, TIMEFRAME_15M, 100);
        if (candles15m == null || candles15m.size() < superTrendAtrLength + 2) return;

        int len15m = candles15m.size();
        double[] highs15m = new double[len15m];
        double[] lows15m = new double[len15m];
        double[] closes15m = new double[len15m];
        for (int i = 0; i < len15m; i++) {
            Candle c = candles15m.get(i);
            highs15m[i] = c.high().doubleValue();
            lows15m[i] = c.low().doubleValue();
            closes15m[i] = c.close().doubleValue();
        }

        double[] closes1h = context.getClosePrices(underlyingSymbol, TIMEFRAME_1H);
        if (closes1h == null || closes1h.length < rsiPeriod + 2) return;

        double[] superTrend = TechnicalIndicators.calculateSuperTrend(highs15m, lows15m, closes15m,
            superTrendAtrLength, superTrendMultiplier);
        double lastSuperTrend = superTrend[superTrend.length - 1];
        if (Double.isNaN(lastSuperTrend)) return;

        double rsi1h = TechnicalIndicators.calculateRsi(closes1h, rsiPeriod);
        if (Double.isNaN(rsi1h)) return;

        // Check daily max trades limit (0 or negative means unlimited)
        if (maxTradesPerDay > 0 && daily.entriesToday >= maxTradesPerDay) {
            daily.position = Position.FLAT;
            daily.tradeDirection = null;
            log.info("[{}] Daily trade limit reached ({}/{}), skipping re-entry and resetting to FLAT",
                strategyId, daily.entriesToday, maxTradesPerDay);
            return;
        }

        double close15m = closes15m[closes15m.length - 1];
        boolean conditionStillValid = false;
        if (daily.tradeDirection == Direction.BULLISH) {
            conditionStillValid = lastSuperTrend > 0 && rsi1h >= rsiUpperThreshold;
        } else if (daily.tradeDirection == Direction.BEARISH) {
            conditionStillValid = lastSuperTrend < 0 && rsi1h <= rsiLowerThreshold;
        }

        if (!conditionStillValid) {
            daily.position = Position.FLAT;
            daily.tradeDirection = null;
            log.info("[{}] Re-entry conditions not met, position reset", strategyId);
            return;
        }

        // Check if premium has dropped back to entry price. In live mode, refresh the
        // last known premium from a fresh LTP quote so the re-entry SL isn't based on a
        // stale post-exit price.
        if (isLiveMode() && daily.activeShortSymbol != null && kitePcrProvider != null) {
            double ltp = kitePcrProvider.fetchLtp(daily.activeShortSymbol);
            if (ltp > 0) daily.lastPremiumLtp = ltp;
        }
        double currentCheckPremium = isLiveMode() ? daily.lastPremiumLtp : daily.currentPremium;
        if (currentCheckPremium > 0 && currentCheckPremium <= daily.entryPremium * 1.02) {
            log.info("[{}] RE-ENTRY: {} @ ₹{} (was ₹{})",
                strategyId, daily.activeShortSymbol, currentCheckPremium, daily.entryPremium);

            // Update state for re-entry
            daily.entryPremium = currentCheckPremium;
            daily.slPrice = daily.entryPremium * (1.0 + stopLossPct / 100.0);
            daily.position = Position.IN_TRADE;
            daily.candlesSinceEntry = 0;
            daily.entriesToday++;

            // Emit re-entry signal
            String optionType = daily.tradeDirection == Direction.BULLISH ? "PE" : "CE";
            String tag = "ST_" + optionType + "_REENTRY";
            Signal entrySignal = Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(daily.activeShortSymbol)
                .signalType(SignalType.ENTRY_SHORT)
                .quantity(daily.positionQty)
                .price(BigDecimal.valueOf(daily.entryPremium))
                .orderType(OrderType.LIMIT)
                .productType(ProductType.MIS)
                .protectiveStopTrigger(BigDecimal.valueOf(daily.slPrice))
                .tag(tag)
                .timestamp(candle.timestamp())
                .build();
            context.emitSignal(entrySignal);

            if (daily.activeHedgeSymbol != null && daily.hedgePremium > 0) {
                String hedgeTag = "ST_HEDGE_REENTRY";
                Signal hedgeSignal = Signal.builder()
                    .strategyId(strategyId)
                    .targetAccountId(assignedAccountId)
                    .symbol(daily.activeHedgeSymbol)
                    .signalType(SignalType.ENTRY_LONG)
                    .quantity(daily.positionQty)
                    .price(BigDecimal.valueOf(daily.hedgePremium))
                    .orderType(OrderType.LIMIT)
                    .productType(ProductType.MIS)
                    .tag(hedgeTag)
                    .timestamp(candle.timestamp())
                    .build();
                context.emitSignal(hedgeSignal);
            }
        }
    }

    private void squareOffAll(String reason) {
        synchronized (daily) {
            if (daily.position == Position.IN_TRADE) {
                double lastPrice = daily.lastPremiumLtp > 0 ? daily.lastPremiumLtp : daily.entryPremium;
                exitTrade(lastPrice, reason, context != null ? context.now() : Instant.now());
            } else if (daily.position == Position.WAIT_FOR_REENTRY) {
                log.info("[{}] EOD square off reached while in WAIT_FOR_REENTRY. Position reset to FLAT.", strategyId);
                daily.exitTrade();
            }
        }
    }

    private void exitTrade(double exitPrice, String reason, Instant timestamp) {
        if (daily.activeShortSymbol == null) return;

        double pnl = (daily.entryPremium - exitPrice) * daily.positionQty;

        // Exit short leg
        String optionType = daily.tradeDirection == Direction.BULLISH ? "PE" : "CE";
        String tag = "ST_" + optionType + "_" + reason;
        Signal exitSignal = Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(daily.activeShortSymbol)
            .signalType(SignalType.EXIT_SHORT)
            .quantity(daily.positionQty)
            .price(BigDecimal.valueOf(exitPrice))
            .orderType(OrderType.MARKET)
            .productType(ProductType.MIS)
            .tag(tag)
            .timestamp(timestamp)
            .build();
        context.emitSignal(exitSignal);

        // Exit hedge leg if exists
        if (daily.activeHedgeSymbol != null) {
            String hedgeTag = "ST_HEDGE_" + reason;
            Signal hedgeExitSignal = Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(daily.activeHedgeSymbol)
                .signalType(SignalType.EXIT_LONG)
                .quantity(daily.positionQty)
                .price(BigDecimal.valueOf(daily.hedgePremium))
                .orderType(OrderType.MARKET)
                .productType(ProductType.MIS)
                .tag(hedgeTag)
                .timestamp(timestamp)
                .build();
            context.emitSignal(hedgeExitSignal);
        }

        log.info("[{}] EXIT {} {} @ ₹{} | Entry: ₹{} | P&L: ₹{} | Reason: {}",
            strategyId, daily.tradeDirection, daily.activeShortSymbol, exitPrice,
            daily.entryPremium, pnl, reason);

        // Set re-entry state if stopped out
        if ("SL_HIT".equals(reason)) {
            daily.position = Position.WAIT_FOR_REENTRY;
            daily.candlesSinceExit = 0;
            log.info("[{}] Entering WAIT_FOR_REENTRY state, cooldown: {} candles",
                strategyId, reEntryCooldownCandles);
        } else {
            daily.exitTrade();
        }
    }

    // ========== Helper Methods ==========

    private boolean isBlackoutDay() {
        if (context == null || blackoutDays.isEmpty()) return false;
        ZonedDateTime ist = context.now().atZone(IST_ZONE);
        String today = ist.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return blackoutDays.contains(today);
    }

    private boolean isEodTime() {
        if (context == null) return false;
        LocalTime istTime = context.now().atZone(IST_ZONE).toLocalTime();
        return !istTime.isBefore(parsedEodExitTime);
    }

    // ========== Daily State ==========

    private static class DailyState {
        volatile Position position = Position.FLAT;
        volatile Direction tradeDirection = null;
        volatile boolean entryLocked = false;
        volatile int entriesToday = 0;
        volatile int candlesSinceEntry = 0;
        volatile int candlesSinceExit = 0;
        volatile String currentDate = "";

        volatile double entryPremium = 0;
        volatile double slPrice = 0;
        volatile Instant entryTime = null;

        volatile String activeShortSymbol = null;
        volatile String activeHedgeSymbol = null;
        volatile int positionQty = 0;
        volatile double lastPremiumLtp = 0;
        volatile double hedgePremium = 0;

        // Backtest mode tracking
        volatile double lastTickPrice = 0;
        volatile double currentPremium = 0;

        void exitTrade() {
            position = Position.FLAT;
            tradeDirection = null;
            entryPremium = 0;
            slPrice = 0;
            entryTime = null;
            candlesSinceEntry = 0;
            candlesSinceExit = 0;
            activeShortSymbol = null;
            activeHedgeSymbol = null;
            positionQty = 0;
            lastPremiumLtp = 0;
            hedgePremium = 0;
            lastTickPrice = 0;
            currentPremium = 0;
        }

        void reset() {
            exitTrade();
            entryLocked = false;
            entriesToday = 0;
        }
    }

    // ========== Enums ==========

    enum Position { FLAT, IN_TRADE, WAIT_FOR_REENTRY }
    enum Direction { BULLISH, BEARISH }

    // ========== Public getters for backtest inspection ==========

    public Position getPosition() { return daily.position; }
    public Direction getTradeDirection() { return daily.tradeDirection; }
    public int getEntriesToday() { return daily.entriesToday; }
    public double getEntryPremium() { return daily.entryPremium; }
    public double getSlPrice() { return daily.slPrice; }
    public String getActiveShortSymbol() { return daily.activeShortSymbol; }
}
