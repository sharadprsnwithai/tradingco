package com.tradingbot.strategy.impl;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import com.tradingbot.strategy.TechnicalIndicators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vande Bharat Intraday Strategy for F&O Stocks.
 * Execution:
 * Phase 0: 09:25 IST Option Chain OI scan picking Top 5 stocks by |PE Δ OI| + |CE Δ OI|.
 * Phase 1: 5m Breakout detection against PDH/PDL with bounds.
 * Phase 2: Inside candle search (max 6 bars = 30m window).
 * Phase 3: High-volume breakout entry above/below inside candle bounds.
 * Phase 4: Trailing stop, 1:2 RR partial exit (50%), and 10-period EMA exit.
 * Phase 5: 15:10 entry lock & 15:14 automated intraday square-off.
 */
@Component
public class VandeBharatStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(VandeBharatStrategy.class);
    private static final String TIMEFRAME = "5";
    private static final int INSIDE_CANDLE_LIMIT = 6;
    private static final int EMA_PERIOD = 10;

    private final String strategyId;
    private final String assignedAccountId;
    private final List<String> symbols = new CopyOnWriteArrayList<>();
    private final int defaultQuantity;

    private StrategyContext context;
    private volatile boolean enabled = true;
    private volatile boolean entryLocked = false;

    // Per-symbol active strategy state
    private final Map<String, StockState> states = new ConcurrentHashMap<>();

    /**
     * Constructs the Vande Bharat Strategy with configured parameters.
     *
     * @param strategyId       unique strategy identifier
     * @param assignedAccountId broker account ID for order routing
     * @param symbolsStr       comma-separated list of symbols to trade
     * @param defaultQuantity  default order quantity per trade
     */
    public VandeBharatStrategy(
        @Value("${bot.strategies.vande-bharat.id:VANDE_BHARAT_01}") String strategyId,
        @Value("${bot.strategies.vande-bharat.account-id:KITE_USER_01}") String assignedAccountId,
        @Value("${bot.strategies.vande-bharat.symbols:NSE:RELIANCE,NSE:TCS,NSE:INFY,NSE:HDFCBANK,NSE:ICICIBANK}") String symbolsStr,
        @Value("${bot.strategies.vande-bharat.default-quantity:10}") int defaultQuantity
    ) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.defaultQuantity = defaultQuantity;
        if (symbolsStr != null && !symbolsStr.isBlank()) {
            for (String s : symbolsStr.split(",")) {
                symbols.add(s.trim());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getStrategyId() {
        return strategyId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAssignedAccountId() {
        return assignedAccountId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getSubscribedSymbols() {
        return Collections.unmodifiableList(symbols);
    }

    /**
     * {@inheritDoc}
     *
     * @param context strategy context for signal emission and data access
     */
    @Override
    public void init(StrategyContext context) {
        this.context = context;
        for (String sym : symbols) {
            states.put(sym, new StockState(sym));
        }
        log.info("Initialized VandeBharatStrategy '{}' for {} symbols", strategyId, symbols.size());
    }

    /**
     * Phase 0: Updates active trading watchlist based on 09:25 IST Option Chain OI Scan.
     * Selects Top 5 stocks by |PE Δ OI| + |CE Δ OI|.
     */
    public synchronized List<String> updateWatchlistFromOiScan(List<OiScanResult> scanResults) {
        if (scanResults == null || scanResults.isEmpty()) {
            return new ArrayList<>(symbols);
        }

        List<OiScanResult> sorted = new ArrayList<>(scanResults);
        sorted.sort(Comparator.comparingLong(OiScanResult::totalOiChange).reversed());

        List<String> top5 = sorted.stream()
            .limit(5)
            .map(OiScanResult::symbol)
            .toList();

        symbols.clear();
        symbols.addAll(top5);

        states.clear();
        for (String sym : top5) {
            states.put(sym, new StockState(sym));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 [VANDE BHARAT] 09:25 IST TOP 5 OI BREAKOUT WATCHLIST SELECTED:\n");
        for (int i = 0; i < sorted.size() && i < 5; i++) {
            OiScanResult r = sorted.get(i);
            sb.append(String.format("  %d. %s (Total ΔOI: %,d | CE ΔOI: %,d | PE ΔOI: %,d)\n",
                i + 1, r.symbol(), r.totalOiChange(), r.ceOiChange(), r.peOiChange()));
        }
        log.info(sb.toString());

        return top5;
    }

    /**
     * {@inheritDoc}
     *
     * @param tick real-time price tick to evaluate for exit conditions
     */
    @Override
    public void onTick(Tick tick) {
        if (!enabled || tick == null || tick.symbol() == null) return;
        StockState state = states.get(tick.symbol());
        if (state == null) return;

        synchronized (state) {
            if (state.position == TradePosition.IN_TRADE) {
                checkTickExits(state, tick.ltp());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param candle closed 5-minute candle to evaluate for entry or exit
     */
    @Override
    public void onCandle(Candle candle) {
        if (!enabled || candle == null || !TIMEFRAME.equals(candle.timeframe())) return;
        StockState state = states.get(candle.symbol());
        if (state == null) return;

        synchronized (state) {
            if (state.position == TradePosition.IN_TRADE) {
                checkCandleExits(state, candle);
            } else if (!entryLocked) {
                evaluateEntry(state, candle);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param event scheduled event triggering strategy state transitions
     */
    @Override
    public void onSchedule(ScheduledEvent event) {
        log.info("VandeBharatStrategy '{}' received schedule event: {}", strategyId, event.eventType());
        switch (event.eventType()) {
            case ScheduledEvent.PRE_MARKET_SCAN, ScheduledEvent.OI_SCAN -> resetDailyStates();
            case ScheduledEvent.INTRADAY_ENTRY_CUTOFF -> {
                this.entryLocked = true;
                log.info("VandeBharatStrategy entry locked for the day");
            }
            case ScheduledEvent.INTRADAY_SQUARE_OFF -> squareOffAllPositions();
            case ScheduledEvent.MARKET_CLOSE -> resetDailyStates();
        }
    }

    /**
     * Phase 1, 2, 3: Breakout detection, Inside Candle identification, and Entry Trigger.
     */
    private void evaluateEntry(StockState state, Candle candle) {
        // Initialize PDH / PDL if not yet set
        if (state.pdh == null || state.pdl == null) {
            state.pdh = candle.close().multiply(new BigDecimal("1.01"));
            state.pdl = candle.close().multiply(new BigDecimal("0.99"));
        }

        // Phase 1: Breakout Detection
        if (state.breakoutCandle == null) {
            BigDecimal maxLong = state.pdh.multiply(new BigDecimal("1.02"));
            BigDecimal minShort = state.pdl.multiply(new BigDecimal("0.98"));

            if (candle.close().compareTo(state.pdh) > 0 && candle.close().compareTo(maxLong) <= 0) {
                state.breakoutCandle = candle;
                state.direction = Direction.LONG;
                state.insideCandleAttempts = 0;
                state.insideCandle = null;
                log.info("[{}] Phase 1 LONG Breakout detected at {}", state.symbol, candle.close());
            } else if (candle.close().compareTo(state.pdl) < 0 && candle.close().compareTo(minShort) >= 0) {
                state.breakoutCandle = candle;
                state.direction = Direction.SHORT;
                state.insideCandleAttempts = 0;
                state.insideCandle = null;
                log.info("[{}] Phase 1 SHORT Breakout detected at {}", state.symbol, candle.close());
            }
            return;
        }

        // Phase 2: Inside Candle Search (up to 6 candles = 30 min)
        if (state.insideCandle == null) {
            state.insideCandleAttempts++;
            if (isInsideCandle(candle, state.breakoutCandle)) {
                state.insideCandle = candle;
                log.info("[{}] Phase 2 Inside Candle FOUND on attempt {} (High: {}, Low: {})",
                    state.symbol, state.insideCandleAttempts, candle.high(), candle.low());
            } else if (state.insideCandleAttempts >= INSIDE_CANDLE_LIMIT) {
                log.info("[{}] Inside candle limit reached ({}). Resetting breakout.", state.symbol, INSIDE_CANDLE_LIMIT);
                state.resetBreakout();
            }
            return;
        }

        // Phase 3: Entry Trigger
        if (state.direction == Direction.LONG) {
            if (candle.close().compareTo(state.insideCandle.high()) > 0 && candle.volume() > state.insideCandle.volume()) {
                enterTrade(state, Direction.LONG, state.insideCandle.high(), state.insideCandle.low(), candle.close());
            }
        } else if (state.direction == Direction.SHORT) {
            if (candle.close().compareTo(state.insideCandle.low()) < 0 && candle.volume() > state.insideCandle.volume()) {
                enterTrade(state, Direction.SHORT, state.insideCandle.low(), state.insideCandle.high(), candle.close());
            }
        }
    }

    /**
     * Checks if the given candle qualifies as an inside candle relative to the breakout candle.
     *
     * @param candle   the candidate inside candle
     * @param breakout the breakout candle to compare against
     * @return true if the candle's high/low are within the breakout candle's range and volume is lower
     */
    private boolean isInsideCandle(Candle candle, Candle breakout) {
        return candle.high().compareTo(breakout.high()) <= 0
            && candle.low().compareTo(breakout.low()) >= 0
            && candle.volume() <= breakout.volume();
    }

    /**
     * Enters a new trade position for the given symbol and emits an entry signal.
     *
     * @param state          per-symbol strategy state to update
     * @param direction      trade direction (LONG or SHORT)
     * @param entryPrice     the entry trigger price from inside candle bounds
     * @param initialSl      initial stop-loss price
     * @param executionPrice the actual execution price for the signal
     */
    private void enterTrade(StockState state, Direction direction, BigDecimal entryPrice, BigDecimal initialSl, BigDecimal executionPrice) {
        state.position = TradePosition.IN_TRADE;
        state.direction = direction;
        state.entryPrice = entryPrice;
        state.initialStopLoss = initialSl;
        state.trailingStopLoss = initialSl;
        state.stopDistance = entryPrice.subtract(initialSl).abs();
        state.highestPrice = executionPrice;
        state.lowestPrice = executionPrice;
        state.partialExitBooked = false;
        state.remainingQuantity = defaultQuantity;

        SignalType sigType = (direction == Direction.LONG) ? SignalType.ENTRY_LONG : SignalType.ENTRY_SHORT;

        context.emitSignal(Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(state.symbol)
            .signalType(sigType)
            .quantity(defaultQuantity)
            .price(executionPrice)
            .triggerPrice(initialSl)
            .orderType(OrderType.LIMIT)
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .tag("VB_ENTRY_" + direction)
            .build());

        log.info("[{}] Phase 3 TRADE ENTERED {} @ {} | Initial SL: {} | Stop Dist: {}",
            state.symbol, direction, executionPrice, initialSl, state.stopDistance);
    }

    /**
     * Phase 4: Real-time tick trailing stop & 1:2 RR partial exit check.
     */
    private void checkTickExits(StockState state, BigDecimal ltp) {
        if (state.direction == Direction.LONG) {
            state.highestPrice = state.highestPrice.max(ltp);
            BigDecimal newTrailing = state.highestPrice.subtract(state.stopDistance);
            state.trailingStopLoss = state.trailingStopLoss.max(newTrailing);

            // 1:2 RR Partial Exit (50% booking)
            if (!state.partialExitBooked) {
                BigDecimal target2R = state.entryPrice.add(state.stopDistance.multiply(BigDecimal.valueOf(2)));
                if (ltp.compareTo(target2R) >= 0) {
                    int partialQty = state.remainingQuantity / 2;
                    if (partialQty > 0) {
                        state.partialExitBooked = true;
                        state.remainingQuantity -= partialQty;
                        context.emitSignal(Signal.builder()
                            .strategyId(strategyId)
                            .targetAccountId(assignedAccountId)
                            .symbol(state.symbol)
                            .signalType(SignalType.EXIT_PARTIAL_LONG)
                            .quantity(partialQty)
                            .price(ltp)
                            .tag("VB_PARTIAL_EXIT_1:2")
                            .build());
                        log.info("[{}] 50% PARTIAL PROFIT BOOKED at 1:2 RR @ {}", state.symbol, ltp);
                    }
                }
            }

            // Trailing Stop Hit
            if (ltp.compareTo(state.trailingStopLoss) <= 0) {
                exitTrade(state, SignalType.EXIT_LONG, ltp, "TRAILING_STOP_HIT");
            }
        } else if (state.direction == Direction.SHORT) {
            state.lowestPrice = state.lowestPrice.min(ltp);
            BigDecimal newTrailing = state.lowestPrice.add(state.stopDistance);
            state.trailingStopLoss = state.trailingStopLoss.min(newTrailing);

            // 1:2 RR Partial Exit
            if (!state.partialExitBooked) {
                BigDecimal target2R = state.entryPrice.subtract(state.stopDistance.multiply(BigDecimal.valueOf(2)));
                if (ltp.compareTo(target2R) <= 0) {
                    int partialQty = state.remainingQuantity / 2;
                    if (partialQty > 0) {
                        state.partialExitBooked = true;
                        state.remainingQuantity -= partialQty;
                        context.emitSignal(Signal.builder()
                            .strategyId(strategyId)
                            .targetAccountId(assignedAccountId)
                            .symbol(state.symbol)
                            .signalType(SignalType.EXIT_PARTIAL_SHORT)
                            .quantity(partialQty)
                            .price(ltp)
                            .tag("VB_PARTIAL_EXIT_1:2")
                            .build());
                        log.info("[{}] 50% PARTIAL PROFIT BOOKED at 1:2 RR @ {}", state.symbol, ltp);
                    }
                }
            }

            // Trailing Stop Hit
            if (ltp.compareTo(state.trailingStopLoss) >= 0) {
                exitTrade(state, SignalType.EXIT_SHORT, ltp, "TRAILING_STOP_HIT");
            }
        }
    }

    /**
     * Phase 4: 10-period EMA cross exit evaluated on 5m candle close.
     */
    private void checkCandleExits(StockState state, Candle candle) {
        double[] closePrices = context.getClosePrices(state.symbol, TIMEFRAME);
        if (closePrices.length >= EMA_PERIOD) {
            double ema10 = TechnicalIndicators.calculateEma(closePrices, EMA_PERIOD);
            if (!Double.isNaN(ema10)) {
                BigDecimal emaVal = BigDecimal.valueOf(ema10);
                if (state.direction == Direction.LONG && candle.close().compareTo(emaVal) < 0) {
                    exitTrade(state, SignalType.EXIT_LONG, candle.close(), "EMA_10_CROSS_EXIT");
                } else if (state.direction == Direction.SHORT && candle.close().compareTo(emaVal) > 0) {
                    exitTrade(state, SignalType.EXIT_SHORT, candle.close(), "EMA_10_CROSS_EXIT");
                }
            }
        }
    }

    /**
     * Emits an exit signal for the given trade and resets the symbol state.
     *
     * @param state    per-symbol strategy state to exit
     * @param exitType signal type indicating long or short exit
     * @param price    execution price for the exit
     * @param reason   descriptive reason tag for the exit
     */
    private void exitTrade(StockState state, SignalType exitType, BigDecimal price, String reason) {
        context.emitSignal(Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(state.symbol)
            .signalType(exitType)
            .quantity(state.remainingQuantity)
            .price(price)
            .tag("VB_EXIT_" + reason)
            .build());

        log.info("[{}] TRADE EXITED: {} @ {} | Reason: {}", state.symbol, exitType, price, reason);
        state.resetTrade();
    }

    /**
     * Squares off all open positions at end-of-day (15:14 IST) using highest price as reference.
     */
    private void squareOffAllPositions() {
        for (StockState state : states.values()) {
            synchronized (state) {
                if (state.position == TradePosition.IN_TRADE) {
                    SignalType sig = (state.direction == Direction.LONG) ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
                    exitTrade(state, sig, state.highestPrice != null ? state.highestPrice : BigDecimal.ZERO, "EOD_SQUARE_OFF_15:14");
                }
            }
        }
    }

    /**
     * Resets all daily state including entry lock and per-symbol states.
     */
    private void resetDailyStates() {
        this.entryLocked = false;
        for (StockState s : states.values()) {
            s.resetDaily();
        }
    }

    /**
     * Returns the current strategy state for the given symbol.
     *
     * @param symbol the trading symbol
     * @return the StockState for the symbol, or null if not tracked
     */
    public StockState getState(String symbol) {
        return states.get(symbol);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        states.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * {@inheritDoc}
     *
     * @param enabled true to enable, false to pause the strategy
     */
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Represents the result of an Option Chain OI scan for a single symbol.
     *
     * @param symbol        the trading symbol
     * @param ceOiChange    change in Call Option Open Interest
     * @param peOiChange    change in Put Option Open Interest
     * @param totalOiChange absolute total OI change (|CE Δ OI| + |PE Δ OI|)
     */
    public record OiScanResult(String symbol, long ceOiChange, long peOiChange, long totalOiChange) {
        /**
         * Creates an OiScanResult with computed total OI change.
         *
         * @param symbol    the trading symbol
         * @param ceOiChange change in Call Option Open Interest
         * @param peOiChange change in Put Option Open Interest
         * @return a new OiScanResult instance
         */
        public static OiScanResult of(String symbol, long ceOiChange, long peOiChange) {
            return new OiScanResult(symbol, ceOiChange, peOiChange, Math.abs(ceOiChange) + Math.abs(peOiChange));
        }
    }

    public enum Direction { LONG, SHORT }
    public enum TradePosition { FLAT, IN_TRADE }

    public static class StockState {
        public final String symbol;
        public BigDecimal pdh;
        public BigDecimal pdl;
        public Candle breakoutCandle;
        public Direction direction;
        public int insideCandleAttempts;
        public Candle insideCandle;

        public TradePosition position = TradePosition.FLAT;
        public BigDecimal entryPrice;
        public BigDecimal initialStopLoss;
        public BigDecimal trailingStopLoss;
        public BigDecimal stopDistance;
        public BigDecimal highestPrice;
        public BigDecimal lowestPrice;
        public boolean partialExitBooked;
        public int remainingQuantity;

        /**
         * Constructs a StockState for the given trading symbol.
         *
         * @param symbol the trading symbol identifier
         */
        public StockState(String symbol) {
            this.symbol = symbol;
        }

        /**
         * Resets the breakout detection state, clearing breakout candle, direction, and inside candle data.
         */
        public void resetBreakout() {
            this.breakoutCandle = null;
            this.direction = null;
            this.insideCandleAttempts = 0;
            this.insideCandle = null;
        }

        /**
         * Resets the active trade state, including breakout and position data.
         */
        public void resetTrade() {
            resetBreakout();
            this.position = TradePosition.FLAT;
            this.entryPrice = null;
            this.initialStopLoss = null;
            this.trailingStopLoss = null;
            this.stopDistance = null;
            this.highestPrice = null;
            this.lowestPrice = null;
            this.partialExitBooked = false;
            this.remainingQuantity = 0;
        }

        /**
         * Resets all daily state including PDH/PDL levels and active trade data.
         */
        public void resetDaily() {
            resetTrade();
            this.pdh = null;
            this.pdl = null;
        }
    }
}
