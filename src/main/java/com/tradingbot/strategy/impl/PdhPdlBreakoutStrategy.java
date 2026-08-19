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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Previous Day High / Low (PDH/PDL) Range Breakout Strategy.
 * Fully decoupled strategy plugin executing on 5m/15m closed candles with real-time SL/Target monitoring.
 */
@Component
public class PdhPdlBreakoutStrategy implements Strategy {

    private static final Logger log = LoggerFactory.getLogger(PdhPdlBreakoutStrategy.class);
    private static final String TIMEFRAME = "5";

    private final String strategyId;
    private final String assignedAccountId;
    private final List<String> symbols;
    private final int defaultQuantity;

    private StrategyContext context;
    private volatile boolean enabled = true;
    private volatile boolean entryLocked = false;

    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    /**
     * Constructs the PDH/PDL Breakout Strategy with configured parameters.
     *
     * @param strategyId       unique strategy identifier
     * @param assignedAccountId broker account ID for order routing
     * @param symbolsStr       comma-separated list of symbols to trade
     * @param defaultQuantity  default order quantity per trade
     */
    public PdhPdlBreakoutStrategy(
        @Value("${bot.strategies.pdh-pdl.id:PDH_PDL_01}") String strategyId,
        @Value("${bot.strategies.pdh-pdl.account-id:SHOONYA_USER_01}") String assignedAccountId,
        @Value("${bot.strategies.pdh-pdl.symbols:NSE:NIFTY,NSE:BANKNIFTY}") String symbolsStr,
        @Value("${bot.strategies.pdh-pdl.default-quantity:25}") int defaultQuantity
    ) {
        this.strategyId = strategyId;
        this.assignedAccountId = assignedAccountId;
        this.defaultQuantity = defaultQuantity;
        this.symbols = List.of(symbolsStr.split(","));
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
        return symbols;
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
            states.put(sym, new SymbolState(sym));
        }
        log.info("Initialized PdhPdlBreakoutStrategy '{}' for {} symbols", strategyId, symbols.size());
    }

    /**
     * {@inheritDoc}
     *
     * @param tick real-time price tick to evaluate for exit conditions
     */
    @Override
    public void onTick(Tick tick) {
        if (!enabled || tick == null || tick.symbol() == null) return;
        SymbolState state = states.get(tick.symbol());
        if (state == null || !state.inTrade) return;

        synchronized (state) {
            checkTickExits(state, tick.ltp());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param candle closed 5-minute candle to evaluate for breakout entry
     */
    @Override
    public void onCandle(Candle candle) {
        if (!enabled || candle == null || !TIMEFRAME.equals(candle.timeframe())) return;
        SymbolState state = states.get(candle.symbol());
        if (state == null) return;

        synchronized (state) {
            if (!state.inTrade && !entryLocked) {
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
        switch (event.eventType()) {
            case ScheduledEvent.PRE_MARKET_SCAN, ScheduledEvent.MARKET_CLOSE -> {
                this.entryLocked = false;
                states.values().forEach(SymbolState::reset);
            }
            case ScheduledEvent.INTRADAY_ENTRY_CUTOFF -> this.entryLocked = true;
            case ScheduledEvent.INTRADAY_SQUARE_OFF -> squareOffAll();
        }
    }

    /**
     * Evaluates a candle for PDH/PDL breakout entry signals.
     * If PDH/PDL are not yet initialized, sets dynamic baseline from the candle.
     * Emits ENTRY_LONG or ENTRY_SHORT signals when price breaks above PDH or below PDL.
     *
     * @param state  per-symbol strategy state
     * @param candle closed candle to evaluate
     */
    private void evaluateEntry(SymbolState state, Candle candle) {
        if (state.pdh == null || state.pdl == null) {
            // Set dynamic baseline if not provided externally
            state.pdh = candle.close().multiply(new BigDecimal("1.005"));
            state.pdl = candle.close().multiply(new BigDecimal("0.995"));
            return;
        }

        if (candle.close().compareTo(state.pdh) > 0) {
            // Long breakout
            state.inTrade = true;
            state.isLong = true;
            state.entryPrice = candle.close();
            state.stopLoss = candle.low();
            BigDecimal risk = state.entryPrice.subtract(state.stopLoss);
            state.target = state.entryPrice.add(risk.multiply(BigDecimal.valueOf(2)));

            context.emitSignal(Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(state.symbol)
                .signalType(SignalType.ENTRY_LONG)
                .quantity(defaultQuantity)
                .price(candle.close())
                .triggerPrice(state.stopLoss)
                .orderType(OrderType.LIMIT)
                .productType(ProductType.MIS)
                .bookType(BookType.INTRADAY)
                .tag("PDH_BREAKOUT_LONG")
                .build());

            log.info("[{}] PDH Long Breakout @ {} | SL: {} | Target: {}", state.symbol, candle.close(), state.stopLoss, state.target);
        } else if (candle.close().compareTo(state.pdl) < 0) {
            // Short breakout
            state.inTrade = true;
            state.isLong = false;
            state.entryPrice = candle.close();
            state.stopLoss = candle.high();
            BigDecimal risk = state.stopLoss.subtract(state.entryPrice);
            state.target = state.entryPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));

            context.emitSignal(Signal.builder()
                .strategyId(strategyId)
                .targetAccountId(assignedAccountId)
                .symbol(state.symbol)
                .signalType(SignalType.ENTRY_SHORT)
                .quantity(defaultQuantity)
                .price(candle.close())
                .triggerPrice(state.stopLoss)
                .orderType(OrderType.LIMIT)
                .productType(ProductType.MIS)
                .bookType(BookType.INTRADAY)
                .tag("PDL_BREAKOUT_SHORT")
                .build());

            log.info("[{}] PDL Short Breakout @ {} | SL: {} | Target: {}", state.symbol, candle.close(), state.stopLoss, state.target);
        }
    }

    /**
     * Checks real-time tick price against target and stop-loss levels for exit signals.
     *
     * @param state per-symbol strategy state with active trade details
     * @param ltp   last traded price from the tick
     */
    private void checkTickExits(SymbolState state, BigDecimal ltp) {
        if (state.isLong) {
            if (ltp.compareTo(state.target) >= 0) {
                exitTrade(state, SignalType.EXIT_LONG, ltp, "TARGET_HIT_1:2");
            } else if (ltp.compareTo(state.stopLoss) <= 0) {
                exitTrade(state, SignalType.EXIT_LONG, ltp, "SL_HIT");
            }
        } else {
            if (ltp.compareTo(state.target) <= 0) {
                exitTrade(state, SignalType.EXIT_SHORT, ltp, "TARGET_HIT_1:2");
            } else if (ltp.compareTo(state.stopLoss) >= 0) {
                exitTrade(state, SignalType.EXIT_SHORT, ltp, "SL_HIT");
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
    private void exitTrade(SymbolState state, SignalType exitType, BigDecimal price, String reason) {
        context.emitSignal(Signal.builder()
            .strategyId(strategyId)
            .targetAccountId(assignedAccountId)
            .symbol(state.symbol)
            .signalType(exitType)
            .quantity(defaultQuantity)
            .price(price)
            .tag("PDH_EXIT_" + reason)
            .build());

        log.info("[{}] Trade Exited: {} @ {} | Reason: {}", state.symbol, exitType, price, reason);
        state.resetTrade();
    }

    /**
     * Squares off all open positions at end-of-day using entry price as execution reference.
     */
    private void squareOffAll() {
        for (SymbolState state : states.values()) {
            synchronized (state) {
                if (state.inTrade) {
                    SignalType sig = state.isLong ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
                    exitTrade(state, sig, state.entryPrice, "EOD_SQUARE_OFF");
                }
            }
        }
    }

    /**
     * Returns the current strategy state for the given symbol.
     *
     * @param symbol the trading symbol
     * @return the SymbolState for the symbol, or null if not tracked
     */
    public SymbolState getState(String symbol) {
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

    public static class SymbolState {
        public final String symbol;
        public BigDecimal pdh;
        public BigDecimal pdl;
        public boolean inTrade = false;
        public boolean isLong = false;
        public BigDecimal entryPrice;
        public BigDecimal stopLoss;
        public BigDecimal target;

        /**
         * Constructs a SymbolState for the given trading symbol.
         *
         * @param symbol the trading symbol identifier
         */
        public SymbolState(String symbol) {
            this.symbol = symbol;
        }

        /**
         * Resets the active trade state, clearing entry price, stop loss, and target.
         */
        public void resetTrade() {
            this.inTrade = false;
            this.entryPrice = null;
            this.stopLoss = null;
            this.target = null;
        }

        /**
         * Resets all state including PDH/PDL levels and active trade data.
         */
        public void reset() {
            resetTrade();
            this.pdh = null;
            this.pdl = null;
        }
    }
}
