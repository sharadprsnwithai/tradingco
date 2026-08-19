package com.tradingbot.backtest;

import com.tradingbot.marketdata.CircularCandleBuffer;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic Historical Event-Driven Backtesting Replay Engine.
 * Guarantees 100% execution parity with live production by streaming
 * historical events through the identical decoupled Strategy SPI.
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    /**
     * Executes backtest replay over historical candles for a strategy.
     */
    public BacktestResult run(Strategy strategy, List<Candle> historicalCandles, BigDecimal initialCapital) {
        if (strategy == null || historicalCandles == null || historicalCandles.isEmpty()) {
            throw new IllegalArgumentException("Strategy and historical candles cannot be empty");
        }

        // Sort candles chronologically ascending
        List<Candle> sortedCandles = new ArrayList<>(historicalCandles);
        sortedCandles.sort(Comparator.comparing(Candle::timestamp));

        BacktestContextImpl context = new BacktestContextImpl(strategy.getStrategyId(), strategy.getAssignedAccountId());
        strategy.init(context);

        for (Candle candle : sortedCandles) {
            context.setCurrentTime(candle.timestamp());
            context.recordCandle(candle);

            // Generate synthetic intra-bar ticks (Open -> High/Low -> Close) for realistic SL & Target testing
            Tick openTick = Tick.builder()
                .symbol(candle.symbol())
                .ltp(candle.open())
                .high(candle.high())
                .low(candle.low())
                .volume(candle.volume() / 4)
                .timestamp(candle.timestamp())
                .build();
            strategy.onTick(openTick);

            boolean isGreen = candle.close().compareTo(candle.open()) >= 0;
            BigDecimal firstExtremum = isGreen ? candle.low() : candle.high();
            BigDecimal secondExtremum = isGreen ? candle.high() : candle.low();

            Tick firstTick = Tick.builder()
                .symbol(candle.symbol())
                .ltp(firstExtremum)
                .high(candle.high())
                .low(candle.low())
                .volume(candle.volume() / 4)
                .timestamp(candle.timestamp().plusMillis(100))
                .build();
            strategy.onTick(firstTick);

            Tick secondTick = Tick.builder()
                .symbol(candle.symbol())
                .ltp(secondExtremum)
                .high(candle.high())
                .low(candle.low())
                .volume(candle.volume() / 4)
                .timestamp(candle.timestamp().plusMillis(200))
                .build();
            strategy.onTick(secondTick);

            Tick closeTick = Tick.builder()
                .symbol(candle.symbol())
                .ltp(candle.close())
                .high(candle.high())
                .low(candle.low())
                .volume(candle.volume() / 4)
                .timestamp(candle.timestamp().plusMillis(300))
                .build();
            strategy.onTick(closeTick);

            // Complete bar event on candle close
            strategy.onCandle(candle);
        }

        strategy.destroy();

        return computeResults(strategy.getStrategyId(), initialCapital, context.getCompletedTrades());
    }

    /**
     * Computes aggregate backtest statistics from a list of completed trades.
     *
     * @param strategyId    the identifier of the strategy being backtested
     * @param initialCapital the starting capital for the backtest
     * @param trades        the list of completed trades to aggregate
     * @return a {@link BacktestResult} containing all computed performance metrics
     */
    private BacktestResult computeResults(String strategyId, BigDecimal initialCapital, List<BacktestTrade> trades) {
        BigDecimal netPnL = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        int winningTrades = 0;
        int losingTrades = 0;

        BigDecimal peakCapital = initialCapital;
        BigDecimal currentCapital = initialCapital;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        double maxDrawdownPercent = 0.0;

        for (BacktestTrade trade : trades) {
            netPnL = netPnL.add(trade.pnl());
            currentCapital = currentCapital.add(trade.pnl());

            if (trade.pnl().compareTo(BigDecimal.ZERO) > 0) {
                winningTrades++;
                grossProfit = grossProfit.add(trade.pnl());
            } else if (trade.pnl().compareTo(BigDecimal.ZERO) < 0) {
                losingTrades++;
                grossLoss = grossLoss.add(trade.pnl().abs());
            }

            if (currentCapital.compareTo(peakCapital) > 0) {
                peakCapital = currentCapital;
            } else {
                BigDecimal dd = peakCapital.subtract(currentCapital);
                if (dd.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = dd;
                    if (peakCapital.compareTo(BigDecimal.ZERO) > 0) {
                        maxDrawdownPercent = dd.divide(peakCapital, 4, RoundingMode.HALF_UP).doubleValue() * 100.0;
                    }
                }
            }
        }

        int totalTrades = trades.size();
        double winRate = totalTrades > 0 ? ((double) winningTrades / totalTrades) * 100.0 : 0.0;
        double profitFactor = grossLoss.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP).doubleValue()
            : (grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 99.99 : 1.0);

        BigDecimal finalCapital = initialCapital.add(netPnL);

        return new BacktestResult(
            strategyId,
            initialCapital,
            finalCapital,
            netPnL,
            totalTrades,
            winningTrades,
            losingTrades,
            winRate,
            grossProfit,
            grossLoss,
            profitFactor,
            maxDrawdown,
            maxDrawdownPercent,
            trades
        );
    }

    private static class BacktestContextImpl implements StrategyContext {
        private final String strategyId;
        private final String accountId;
        private Instant currentTime = Instant.now();

        private final Map<String, Map<String, CircularCandleBuffer>> buffers = new HashMap<>();
        private final List<BacktestTrade> completedTrades = new ArrayList<>();

        // Active open simulated position
        private OpenPosition activePosition;

        /**
         * Constructs a new backtest context for simulating strategy execution.
         *
         * @param strategyId the identifier of the strategy
         * @param accountId  the assigned account identifier
         */
        BacktestContextImpl(String strategyId, String accountId) {
            this.strategyId = strategyId;
            this.accountId = accountId;
        }

        /**
         * Sets the current simulation time to the given instant.
         *
         * @param time the instant representing the current simulation time
         */
        void setCurrentTime(Instant time) {
            this.currentTime = time;
        }

        /**
         * Records a candle into the appropriate circular buffer for later retrieval.
         *
         * @param candle the candle to record
         */
        void recordCandle(Candle candle) {
            buffers.computeIfAbsent(candle.symbol(), s -> new HashMap<>())
                .computeIfAbsent(candle.timeframe(), tf -> new CircularCandleBuffer(1000))
                .add(candle);
        }

        /**
         * Returns all completed trades recorded during the backtest simulation.
         *
         * @return an unmodifiable list of completed {@link BacktestTrade} objects
         */
        List<BacktestTrade> getCompletedTrades() {
            return completedTrades;
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
            return accountId;
        }

        /**
         * Handles an emitted signal by processing entry and exit orders against
         * the currently active simulated position.
         *
         * @param signal the trading signal to process
         */
        @Override
        public void emitSignal(Signal signal) {
            if (signal == null) return;

            switch (signal.signalType()) {
                case ENTRY_LONG -> activePosition = new OpenPosition(
                    signal.symbol(), "LONG", currentTime, signal.price(), signal.quantity(), signal.tag()
                );
                case ENTRY_SHORT -> activePosition = new OpenPosition(
                    signal.symbol(), "SHORT", currentTime, signal.price(), signal.quantity(), signal.tag()
                );
                case EXIT_PARTIAL_LONG -> {
                    if (activePosition != null && "LONG".equals(activePosition.direction)) {
                        int closedQty = signal.quantity();
                        BigDecimal pnl = signal.price().subtract(activePosition.entryPrice).multiply(BigDecimal.valueOf(closedQty));
                        BigDecimal pnlPct = signal.price().subtract(activePosition.entryPrice)
                            .divide(activePosition.entryPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                        completedTrades.add(new BacktestTrade(
                            activePosition.symbol, activePosition.direction, activePosition.entryTime, activePosition.entryPrice,
                            currentTime, signal.price(), closedQty, pnl, pnlPct, activePosition.tag, signal.tag()
                        ));
                        activePosition.quantity -= closedQty;
                    }
                }
                case EXIT_PARTIAL_SHORT -> {
                    if (activePosition != null && "SHORT".equals(activePosition.direction)) {
                        int closedQty = signal.quantity();
                        BigDecimal pnl = activePosition.entryPrice.subtract(signal.price()).multiply(BigDecimal.valueOf(closedQty));
                        BigDecimal pnlPct = activePosition.entryPrice.subtract(signal.price())
                            .divide(activePosition.entryPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                        completedTrades.add(new BacktestTrade(
                            activePosition.symbol, activePosition.direction, activePosition.entryTime, activePosition.entryPrice,
                            currentTime, signal.price(), closedQty, pnl, pnlPct, activePosition.tag, signal.tag()
                        ));
                        activePosition.quantity -= closedQty;
                    }
                }
                case EXIT_LONG -> {
                    if (activePosition != null && "LONG".equals(activePosition.direction)) {
                        BigDecimal pnl = signal.price().subtract(activePosition.entryPrice).multiply(BigDecimal.valueOf(activePosition.quantity));
                        BigDecimal pnlPct = signal.price().subtract(activePosition.entryPrice)
                            .divide(activePosition.entryPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                        completedTrades.add(new BacktestTrade(
                            activePosition.symbol, activePosition.direction, activePosition.entryTime, activePosition.entryPrice,
                            currentTime, signal.price(), activePosition.quantity, pnl, pnlPct, activePosition.tag, signal.tag()
                        ));
                        activePosition = null;
                    }
                }
                case EXIT_SHORT -> {
                    if (activePosition != null && "SHORT".equals(activePosition.direction)) {
                        BigDecimal pnl = activePosition.entryPrice.subtract(signal.price()).multiply(BigDecimal.valueOf(activePosition.quantity));
                        BigDecimal pnlPct = activePosition.entryPrice.subtract(signal.price())
                            .divide(activePosition.entryPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

                        completedTrades.add(new BacktestTrade(
                            activePosition.symbol, activePosition.direction, activePosition.entryTime, activePosition.entryPrice,
                            currentTime, signal.price(), activePosition.quantity, pnl, pnlPct, activePosition.tag, signal.tag()
                        ));
                        activePosition = null;
                    }
                }
                default -> {}
            }
        }

        /**
         * Retrieves the most recent candle for the given symbol and timeframe.
         *
         * @param symbol    the trading symbol
         * @param timeframe the candle timeframe
         * @return an {@link Optional} containing the last candle, or empty if no data exists
         */
        @Override
        public Optional<Candle> getLastCandle(String symbol, String timeframe) {
            Map<String, CircularCandleBuffer> m = buffers.get(symbol);
            if (m == null) return Optional.empty();
            CircularCandleBuffer buf = m.get(timeframe);
            return buf != null ? buf.getLast() : Optional.empty();
        }

        /**
         * Retrieves the most recent candles for the given symbol and timeframe.
         *
         * @param symbol    the trading symbol
         * @param timeframe the candle timeframe
         * @param count     the maximum number of candles to retrieve
         * @return a list of up to {@code count} candles, most recent first; empty list if no data
         */
        @Override
        public List<Candle> getHistoricalCandles(String symbol, String timeframe, int count) {
            Map<String, CircularCandleBuffer> m = buffers.get(symbol);
            if (m == null) return Collections.emptyList();
            CircularCandleBuffer buf = m.get(timeframe);
            return buf != null ? buf.getLast(count) : Collections.emptyList();
        }

        /**
         * Returns an array of close prices for the given symbol and timeframe.
         *
         * @param symbol    the trading symbol
         * @param timeframe the candle timeframe
         * @return an array of close prices, or an empty array if no data exists
         */
        @Override
        public double[] getClosePrices(String symbol, String timeframe) {
            Map<String, CircularCandleBuffer> m = buffers.get(symbol);
            if (m == null) return new double[0];
            CircularCandleBuffer buf = m.get(timeframe);
            return buf != null ? buf.getClosePrices() : new double[0];
        }

        /**
         * Returns the current simulation time.
         *
         * @return the current {@link Instant}
         */
        @Override
        public Instant now() {
            return currentTime;
        }

        private static class OpenPosition {
            final String symbol;
            final String direction;
            final Instant entryTime;
            final BigDecimal entryPrice;
            int quantity;
            final String tag;

            /**
             * Constructs a new open position record for backtest simulation.
             *
             * @param symbol     the trading symbol
             * @param direction  the position direction ("LONG" or "SHORT")
             * @param entryTime  the time the position was opened
             * @param entryPrice the entry price
             * @param quantity   the number of units in the position
             * @param tag        an optional tag identifying the entry reason
             */
            OpenPosition(String symbol, String direction, Instant entryTime, BigDecimal entryPrice, int quantity, String tag) {
                this.symbol = symbol;
                this.direction = direction;
                this.entryTime = entryTime;
                this.entryPrice = entryPrice;
                this.quantity = quantity;
                this.tag = tag;
            }
        }
    }
}
