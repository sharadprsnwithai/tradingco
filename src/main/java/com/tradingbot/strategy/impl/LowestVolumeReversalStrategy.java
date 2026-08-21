package com.tradingbot.strategy.impl;

import com.tradingbot.instrument.LotSizeService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.nse.NseGainerLoser;
import com.tradingbot.nse.NseIndiaClient;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lowest Volume Reversal & Continuation Intraday Strategy for F&O Stocks.
 *
 * Execution:
 * Phase 0: 09:26 IST Stock Selection via NSE Top Gainers/Losers.
 * Phase 1: 09:15-09:25 IST Observation — collect candles, skip first candle if >5% move.
 * Phase 2: On each 5m candle close — detect momentum, find lowest volume pullback, enter on break.
 * Phase 3: Real-time tick trailing stop + 1:2 RR partial exit (50%) → breakeven trailing.
 * Phase 4: 15:00 IST Hard Exit — close all positions.
 *
 * @see <a href="lowest_volume_reversal.md">Strategy Specification</a>
 */
@Component
public class LowestVolumeReversalStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalStrategy.class);
    private static final String TIMEFRAME = "5";
    private static final double FIRST_CANDLE_DISQUALIFY_THRESHOLD = 5.0;

    private final String strategyId;
    private final String assignedAccountId;
    private final List<String> symbols = new CopyOnWriteArrayList<>();
    private final int maxTradesPerDay;
    private final double minRrRatio;
    private final int momentumCandles;

    private final NseIndiaClient nseClient;
    private final LotSizeService lotSizeService;

    private StrategyContext context;
    private volatile boolean enabled = true;
    private volatile boolean entryLocked = false;
    private volatile int dailyTradeCount = 0;

    // Per-symbol active strategy state
    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    // Daily stock selection results
    private final Set<String> longCandidates = ConcurrentHashMap.newKeySet();
    private final Set<String> shortCandidates = ConcurrentHashMap.newKeySet();

    /**
     * Constructs the Lowest Volume Reversal Strategy with configured parameters.
     *
     * @param strategyId        unique strategy identifier
     * @param assignedAccountId broker account ID for order routing
     * @param symbolsStr        comma-separated list of default symbols to trade
     * @param maxTradesPerDay   maximum number of trades per day
     * @param minRrRatio        minimum risk-to-reward ratio for entry validation
     * @param momentumCandles   number of consecutive candles to confirm momentum
     * @param nseClient         NSE India API client for stock selection
     * @param lotSizeService    service to fetch F&O lot sizes from Kite
     */
    public LowestVolumeReversalStrategy(
        @Value("${bot.strategies.lowest-volume-reversal.id:LOWEST_VOL_REV_01}") String strategyId,
        @Value("${bot.strategies.lowest-volume-reversal.account-id:KITE_USER_01}") String assignedAccountId,
        @Value("${bot.strategies.lowest-volume-reversal.symbols:}") String symbolsStr,
        @Value("${bot.strategies.lowest-volume-reversal.max-trades-per-day:2}") int maxTradesPerDay,
        @Value("${bot.strategies.lowest-volume-reversal.min-rr-ratio:2.0}") double minRrRatio,
        @Value("${bot.strategies.lowest-volume-reversal.momentum-candles:2}") int momentumCandles,
        NseIndiaClient nseClient,
        LotSizeService lotSizeService
    ) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.maxTradesPerDay = maxTradesPerDay;
        this.minRrRatio = minRrRatio;
        this.momentumCandles = momentumCandles;
        this.nseClient = nseClient;
        this.lotSizeService = lotSizeService;
        if (symbolsStr != null && !symbolsStr.isBlank()) {
            for (String s : symbolsStr.split(",")) {
                symbols.add(s.trim());
            }
        }
    }

    @Override
    public String getStrategyId() {
        return strategyId;
    }

    @Override
    public String getAssignedAccountId() {
        return assignedAccountId;
    }

    @Override
    public List<String> getSubscribedSymbols() {
        return Collections.unmodifiableList(symbols);
    }

    @Override
    public void init(StrategyContext context) {
        this.context = context;
        // Initialize states for any pre-configured symbols
        for (String sym : symbols) {
            states.put(sym, new SymbolState(sym));
        }
        log.info("Initialized LowestVolumeReversalStrategy '{}' with {} pre-configured symbols (dynamic selection at 09:26)",
            strategyId, symbols.size());
    }

    @Override
    public void onTick(Tick tick) {
        if (!enabled || tick == null || tick.symbol() == null) return;
        SymbolState state = states.get(tick.symbol());
        if (state == null) return;

        synchronized (state) {
            if (state.position == TradePosition.IN_TRADE) {
                checkTickExits(state, tick.ltp());
            }
        }
    }

    @Override
    public void onCandle(Candle candle) {
        if (!enabled || candle == null || !TIMEFRAME.equals(candle.timeframe())) return;
        SymbolState state = states.get(candle.symbol());
        if (state == null) return;

        synchronized (state) {
            if (state.position == TradePosition.IN_TRADE) {
                checkCandleExits(state, candle);
            } else if (!entryLocked && dailyTradeCount < maxTradesPerDay) {
                evaluateEntry(state, candle);
            }
        }
    }

    @Override
    public void onSchedule(ScheduledEvent event) {
        log.info("LowestVolumeReversalStrategy '{}' received schedule event: {}", strategyId, event.eventType());
        switch (event.eventType()) {
            case ScheduledEvent.PRE_MARKET_SCAN -> resetDailyStates();
            case ScheduledEvent.STOCK_SELECTION_SCAN -> performStockSelection();
            case ScheduledEvent.MARKET_OPEN -> {
                // Observation phase begins at 09:15
                log.info("Observation phase started — collecting candles, no trading until 09:25");
            }
            case ScheduledEvent.INTRADAY_ENTRY_CUTOFF -> {
                this.entryLocked = true;
                log.info("LowestVolumeReversalStrategy entry locked for the day");
            }
            case ScheduledEvent.INTRADAY_SQUARE_OFF -> squareOffAllPositions();
            case ScheduledEvent.MARKET_CLOSE -> resetDailyStates();
        }
    }

    /**
     * Phase 0: Fetches NSE Top Gainers/Losers and updates candidate watchlists.
     */
    private void performStockSelection() {
        Mono<List<NseGainerLoser>> gainersMono = nseClient.fetchGainers();
        Mono<List<NseGainerLoser>> losersMono = nseClient.fetchLosers();

        Mono.zip(gainersMono, losersMono)
            .subscribe(tuple -> {
                List<NseGainerLoser> gainers = tuple.getT1();
                List<NseGainerLoser> losers = tuple.getT2();

                longCandidates.clear();
                shortCandidates.clear();

                for (NseGainerLoser g : gainers) {
                    String symbol = "NSE:" + g.symbol();
                    longCandidates.add(symbol);
                    // Ensure symbol is in the subscribed list
                    if (!symbols.contains(symbol)) {
                        symbols.add(symbol);
                        states.put(symbol, new SymbolState(symbol));
                    }
                }

                for (NseGainerLoser l : losers) {
                    String symbol = "NSE:" + l.symbol();
                    shortCandidates.add(symbol);
                    // Ensure symbol is in the subscribed list
                    if (!symbols.contains(symbol)) {
                        symbols.add(symbol);
                        states.put(symbol, new SymbolState(symbol));
                    }
                }

                log.info("Stock Selection Complete — Long candidates: {} | Short candidates: {}",
                    longCandidates, shortCandidates);

                // Symbols are now in the list — deterministically sync broker subscriptions
                // so ticks/candles for the newly added symbols are ingested from this point.
                if (context != null) {
                    context.requestSubscriptionSync();
                    log.info("Subscription sync requested for {} symbols after stock selection", symbols.size());
                }
            });
    }

    /**
     * Phase 1 & 2: Evaluates entry conditions on each closed 5m candle.
     * Detects momentum, identifies lowest-volume pullback, and triggers entry.
     */
    private void evaluateEntry(SymbolState state, Candle candle) {
        // Add candle to day's history
        state.dayCandles.add(candle);

        // Phase 1: Disqualification — skip if first candle moves >5%
        if (state.dayCandles.size() == 1) {
            BigDecimal open = candle.open();
            BigDecimal close = candle.close();
            if (open.compareTo(BigDecimal.ZERO) > 0) {
                double movePercent = close.subtract(open).abs()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(open, 2, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
                if (movePercent >= FIRST_CANDLE_DISQUALIFY_THRESHOLD) {
                    log.info("[{}] DISQUALIFIED — First candle moved {:.1f}%", state.symbol, movePercent);
                    state.disqualified = true;
                    return;
                }
            }
            return; // Need at least 2 candles for momentum
        }

        if (state.disqualified) return;

        // Phase 2: Check for momentum followed by pullback
        if (state.setupPhase == SetupPhase.WAITING_FOR_MOMENTUM) {
            detectMomentum(state, candle);
        } else if (state.setupPhase == SetupPhase.WAITING_FOR_PULLBACK) {
            detectPullback(state, candle);
        } else if (state.setupPhase == SetupPhase.WAITING_FOR_ENTRY) {
            checkEntryTrigger(state, candle);
        }
    }

    /**
     * Detects consecutive momentum candles in one direction.
     */
    private void detectMomentum(SymbolState state, Candle candle) {
        boolean isGreen = candle.close().compareTo(candle.open()) > 0;
        boolean isRed = candle.close().compareTo(candle.open()) < 0;

        if (state.pendingDirection == null) {
            // Start tracking from first momentum candle
            if (isGreen) {
                state.pendingDirection = Direction.LONG;
                state.consecutiveMomentum = 1;
            } else if (isRed) {
                state.pendingDirection = Direction.SHORT;
                state.consecutiveMomentum = 1;
            }
            return;
        }

        // Continue tracking same direction
        if (state.pendingDirection == Direction.LONG && isGreen) {
            state.consecutiveMomentum++;
        } else if (state.pendingDirection == Direction.SHORT && isRed) {
            state.consecutiveMomentum++;
        } else {
            // Direction changed — restart tracking
            if (isGreen) {
                state.pendingDirection = Direction.LONG;
                state.consecutiveMomentum = 1;
            } else if (isRed) {
                state.pendingDirection = Direction.SHORT;
                state.consecutiveMomentum = 1;
            } else {
                state.pendingDirection = null;
                state.consecutiveMomentum = 0;
            }
            return;
        }

        // Momentum confirmed
        if (state.consecutiveMomentum >= momentumCandles) {
            state.setupPhase = SetupPhase.WAITING_FOR_PULLBACK;
            log.info("[{}] Momentum detected: {} consecutive {} candles — waiting for pullback",
                state.symbol, state.consecutiveMomentum,
                state.pendingDirection == Direction.LONG ? "GREEN" : "RED");
        }
    }

    /**
     * Detects opposite-color pullback candle with lowest volume of the day.
     */
    private void detectPullback(SymbolState state, Candle candle) {
        boolean isPullback = false;

        if (state.pendingDirection == Direction.LONG) {
            // Long setup: need a RED pullback candle
            isPullback = candle.close().compareTo(candle.open()) < 0;
        } else if (state.pendingDirection == Direction.SHORT) {
            // Short setup: need a GREEN pullback candle
            isPullback = candle.close().compareTo(candle.open()) > 0;
        }

        if (!isPullback) {
            // Not a pullback — momentum may have extended, reset
            log.info("[{}] Pullback not found — candle is not opposite color. Resetting.", state.symbol);
            state.resetSetup();
            return;
        }

        // Check if this pullback candle has the LOWEST volume of ALL candles today
        if (!isLowestVolumeOfDay(state, candle)) {
            log.info("[{}] Pullback candle volume {} is not the lowest of the day — waiting",
                state.symbol, candle.volume());
            return;
        }

        // Pullback with lowest volume found
        state.pullbackCandle = candle;
        state.setupPhase = SetupPhase.WAITING_FOR_ENTRY;
        log.info("[{}] Pullback candle FOUND — Low: {}, High: {}, Volume: {} (lowest of day)",
            state.symbol, candle.low(), candle.high(), candle.volume());
    }

    /**
     * Checks if the given candle has the lowest volume among all candles seen today.
     */
    private boolean isLowestVolumeOfDay(SymbolState state, Candle candidate) {
        for (Candle c : state.dayCandles) {
            if (c == candidate) continue;
            if (c.volume() < candidate.volume()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if price breaks above/below pullback candle to trigger entry.
     */
    private void checkEntryTrigger(SymbolState state, Candle candle) {
        if (state.pullbackCandle == null) return;

        if (state.pendingDirection == Direction.LONG) {
            // Entry when price breaks above pullback candle high
            if (candle.close().compareTo(state.pullbackCandle.high()) > 0) {
                enterTrade(state, Direction.LONG, state.pullbackCandle.high(), state.pullbackCandle.low(), candle.close());
            }
        } else if (state.pendingDirection == Direction.SHORT) {
            // Entry when price breaks below pullback candle low
            if (candle.close().compareTo(state.pullbackCandle.low()) < 0) {
                enterTrade(state, Direction.SHORT, state.pullbackCandle.low(), state.pullbackCandle.high(), candle.close());
            }
        }
    }

    /**
     * Enters a new trade position and emits an entry signal.
     * Quantity = 2 × lot_size for the stock.
     */
    private void enterTrade(SymbolState state, Direction direction, BigDecimal entryPrice, BigDecimal initialSl, BigDecimal executionPrice) {
        int orderQuantity = lotSizeService.getOrderQuantity(state.symbol);

        state.position = TradePosition.IN_TRADE;
        state.direction = direction;
        state.entryPrice = entryPrice;
        state.initialStopLoss = initialSl;
        state.trailingStopLoss = initialSl;
        state.stopDistance = entryPrice.subtract(initialSl).abs();
        state.highestPrice = executionPrice;
        state.lowestPrice = executionPrice;
        state.partialExitBooked = false;
        state.remainingQuantity = orderQuantity;
        dailyTradeCount++;

        SignalType sigType = (direction == Direction.LONG) ? SignalType.ENTRY_LONG : SignalType.ENTRY_SHORT;

        context.emitSignal(Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(state.symbol)
            .signalType(sigType)
            .quantity(orderQuantity)
            .price(executionPrice)
            .triggerPrice(initialSl)
            .orderType(OrderType.LIMIT)
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .tag("LVR_ENTRY_" + direction)
            .build());

        log.info("[{}] TRADE ENTERED {} @ {} | Qty: {} (2 lots) | Initial SL: {} | Stop Dist: {} | Trade {}/{}",
            state.symbol, direction, executionPrice, orderQuantity, initialSl, state.stopDistance,
            dailyTradeCount, maxTradesPerDay);
    }

    /**
     * Phase 3: Real-time tick trailing stop & 1:2 RR partial exit check.
     */
    private void checkTickExits(SymbolState state, BigDecimal ltp) {
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
                        // Move SL to breakeven after partial exit
                        state.trailingStopLoss = state.entryPrice;
                        context.emitSignal(Signal.builder()
                            .strategyId(strategyId)
                            .targetAccountId(assignedAccountId)
                            .symbol(state.symbol)
                            .signalType(SignalType.EXIT_PARTIAL_LONG)
                            .quantity(partialQty)
                            .price(ltp)
                            .tag("LVR_PARTIAL_EXIT_1:2")
                            .build());
                        log.info("[{}] 50% PARTIAL PROFIT BOOKED at 1:2 RR @ {} — SL moved to breakeven", state.symbol, ltp);
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
                        // Move SL to breakeven after partial exit
                        state.trailingStopLoss = state.entryPrice;
                        context.emitSignal(Signal.builder()
                            .strategyId(strategyId)
                            .targetAccountId(assignedAccountId)
                            .symbol(state.symbol)
                            .signalType(SignalType.EXIT_PARTIAL_SHORT)
                            .quantity(partialQty)
                            .price(ltp)
                            .tag("LVR_PARTIAL_EXIT_1:2")
                            .build());
                        log.info("[{}] 50% PARTIAL PROFIT BOOKED at 1:2 RR @ {} — SL moved to breakeven", state.symbol, ltp);
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
     * Checks candle-based exit conditions (e.g., end-of-day cutoff).
     */
    private void checkCandleExits(SymbolState state, Candle candle) {
        // No additional candle-based exits — all managed via tick trailing
    }

    /**
     * Emits an exit signal and resets the symbol state.
     */
    private void exitTrade(SymbolState state, SignalType exitType, BigDecimal price, String reason) {
        context.emitSignal(Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(state.symbol)
            .signalType(exitType)
            .quantity(state.remainingQuantity)
            .price(price)
            .tag("LVR_EXIT_" + reason)
            .build());

        log.info("[{}] TRADE EXITED: {} @ {} | Reason: {}", state.symbol, exitType, price, reason);
        state.resetTrade();
    }

    /**
     * Squares off all open positions at 15:00 IST hard exit.
     */
    private void squareOffAllPositions() {
        for (SymbolState state : states.values()) {
            synchronized (state) {
                if (state.position == TradePosition.IN_TRADE) {
                    SignalType sig = (state.direction == Direction.LONG) ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
                    exitTrade(state, sig, state.highestPrice != null ? state.highestPrice : BigDecimal.ZERO, "HARD_EXIT_15:00");
                }
            }
        }
    }

    /**
     * Resets all daily state.
     */
    private void resetDailyStates() {
        this.entryLocked = false;
        this.dailyTradeCount = 0;
        longCandidates.clear();
        shortCandidates.clear();
        nseClient.clearCache();
        for (SymbolState s : states.values()) {
            s.resetDaily();
        }
    }

    /**
     * Returns the current strategy state for the given symbol.
     */
    public SymbolState getState(String symbol) {
        return states.get(symbol);
    }

    @Override
    public void destroy() {
        states.clear();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the set of current long candidates from NSE scan.
     */
    public Set<String> getLongCandidates() {
        return Collections.unmodifiableSet(longCandidates);
    }

    /**
     * Returns the set of current short candidates from NSE scan.
     */
    public Set<String> getShortCandidates() {
        return Collections.unmodifiableSet(shortCandidates);
    }

    public enum Direction { LONG, SHORT }
    public enum TradePosition { FLAT, IN_TRADE }
    public enum SetupPhase { WAITING_FOR_MOMENTUM, WAITING_FOR_PULLBACK, WAITING_FOR_ENTRY }

    public static class SymbolState {
        public final String symbol;
        public final List<Candle> dayCandles = new ArrayList<>();

        public boolean disqualified = false;
        public SetupPhase setupPhase = SetupPhase.WAITING_FOR_MOMENTUM;
        public Direction pendingDirection = null;
        public int consecutiveMomentum = 0;
        public Candle pullbackCandle = null;

        public TradePosition position = TradePosition.FLAT;
        public Direction direction = null;
        public BigDecimal entryPrice;
        public BigDecimal initialStopLoss;
        public BigDecimal trailingStopLoss;
        public BigDecimal stopDistance;
        public BigDecimal highestPrice;
        public BigDecimal lowestPrice;
        public boolean partialExitBooked;
        public int remainingQuantity;

        public SymbolState(String symbol) {
            this.symbol = symbol;
        }

        /**
         * Resets the setup detection state for a new setup attempt.
         */
        public void resetSetup() {
            this.setupPhase = SetupPhase.WAITING_FOR_MOMENTUM;
            this.pendingDirection = null;
            this.consecutiveMomentum = 0;
            this.pullbackCandle = null;
        }

        /**
         * Resets the active trade state.
         */
        public void resetTrade() {
            resetSetup();
            this.position = TradePosition.FLAT;
            this.direction = null;
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
         * Resets all daily state.
         */
        public void resetDaily() {
            resetTrade();
            this.dayCandles.clear();
            this.disqualified = false;
        }
    }
}
