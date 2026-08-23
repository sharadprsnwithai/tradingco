package com.tradingbot.strategy.impl;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for IntradayTrendMomentumOptionSellingStrategy.
 */
class IntradayTrendMomentumOptionSellingStrategyTest {

    private IntradayTrendMomentumOptionSellingStrategy strategy;
    private MockStrategyContext context;
    private List<Signal> emittedSignals;

    @BeforeEach
    void setUp() {
        strategy = new IntradayTrendMomentumOptionSellingStrategy(
            "TEST_STRATEGY", "TEST_ACCOUNT", "NIFTY_FUT"
        );
        emittedSignals = new ArrayList<>();
        context = new MockStrategyContext(emittedSignals);
        strategy.init(context);
    }

    @Test
    void testStrategyInitialization() {
        assertEquals("TEST_STRATEGY", strategy.getStrategyId());
        assertEquals("TEST_ACCOUNT", strategy.getAssignedAccountId());
        assertTrue(strategy.isEnabled());
    }

    @Test
    void testDisableStrategy() {
        strategy.setEnabled(false);
        assertFalse(strategy.isEnabled());
    }

    @Test
    void testOnTickWhileDisabled() {
        strategy.setEnabled(false);
        strategy.onTick(null);
    }

    @Test
    void testOnCandleWhileDisabled() {
        strategy.setEnabled(false);
        strategy.onCandle(null);
    }

    @Test
    void testResetOnDestroy() {
        strategy.destroy();
        assertEquals(0, strategy.getEntriesToday());
    }

    @Test
    void testGetPositionDefault() {
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
    }

    @Test
    void testGetTradeDirectionDefault() {
        assertNull(strategy.getTradeDirection());
    }

    @Test
    void testSuperTrendBullishAndRsiBullish_GeneratesPeShortSignal() {
        // 1. Arrange: 15m candles with strong uptrend (Supertrend Bullish)
        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 50.0;
            context.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
        }
        // 2. Arrange: 1h candles with RSI > 50
        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 100.0;
            context.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        // 3. Act: Trigger 15m candle
        Candle triggerCandle = createCandle("15", 23250, 23300, 23240, 23290, 25);
        context.addCandle15m(triggerCandle);
        strategy.onCandle(triggerCandle);

        // 4. Assert
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, strategy.getPosition());
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Direction.BULLISH, strategy.getTradeDirection());
        assertEquals(1, emittedSignals.size(), "Should emit entry short signal for PE");
        assertEquals(SignalType.ENTRY_SHORT, emittedSignals.get(0).signalType());
        assertEquals(1, strategy.getEntriesToday());
    }

    @Test
    void testSuperTrendBearishAndRsiBearish_GeneratesCeShortSignal() {
        // 1. Arrange: 15m candles in downtrend (Supertrend Bearish)
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 50.0;
            context.addCandle15m(createCandle("15", p, p + 10, p - 30, p - 20, i));
        }
        // 2. Arrange: 1h candles with RSI < 50
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 100.0;
            context.addCandle1h(createCandle("60", p, p + 20, p - 50, p - 40, i));
        }

        // 3. Act: Trigger 15m candle
        Candle triggerCandle = createCandle("15", 21750, 21760, 21700, 21710, 25);
        context.addCandle15m(triggerCandle);
        strategy.onCandle(triggerCandle);

        // 4. Assert
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, strategy.getPosition());
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Direction.BEARISH, strategy.getTradeDirection());
        assertEquals(1, emittedSignals.size(), "Should emit entry short signal for CE");
        assertEquals(SignalType.ENTRY_SHORT, emittedSignals.get(0).signalType());
        assertEquals(1, strategy.getEntriesToday());
    }

    @Test
    void testStopLossHit_TransitionsToWaitForReentry_AndDoesNotEmitDoubleExitOnEod() {
        // 1. Enter trade
        testSuperTrendBullishAndRsiBullish_GeneratesPeShortSignal();
        emittedSignals.clear();

        // 2. Trigger Stop-Loss via Tick (estimated premium rising above slPrice)
        double entryPrice = 23290;
        Tick slTick = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(entryPrice - 2000.0))
            .timestamp(Instant.now())
            .build();
        strategy.onTick(slTick);

        // 3. Assert exited to WAIT_FOR_REENTRY
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.WAIT_FOR_REENTRY, strategy.getPosition());
        assertEquals(1, emittedSignals.size());
        assertEquals(SignalType.EXIT_SHORT, emittedSignals.get(0).signalType());

        // 4. Trigger EOD Square-Off event
        emittedSignals.clear();
        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_SQUARE_OFF));

        // 5. Must NOT emit a duplicate exit signal
        assertTrue(emittedSignals.isEmpty(), "Must not emit duplicate exit order on EOD when already flat");
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
    }

    @Test
    void testNon15mCandle_DoesNotTriggerEntry() {
        // 15m and 1h data populated
        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 50.0;
            context.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            context.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        // Send a 1-minute candle
        Candle candle1m = createCandle("1", 23250, 23300, 23240, 23290, 25);
        strategy.onCandle(candle1m);

        // Should ignore 1m candle and remain FLAT
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
        assertTrue(emittedSignals.isEmpty());
    }

    @Test
    void testNoMaxTradeLimitByDefault() {
        // First trade
        testSuperTrendBullishAndRsiBullish_GeneratesPeShortSignal();
        assertEquals(1, strategy.getEntriesToday());

        // Exit first trade via SL
        double entryPrice = 23290;
        Tick slTick = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(entryPrice - 2000.0))
            .timestamp(Instant.now())
            .build();
        strategy.onTick(slTick);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.WAIT_FOR_REENTRY, strategy.getPosition());

        // Invalidate condition: replace 1h candles with strong downtrend (RSI < 50)
        context.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 100.0;
            context.addCandle1h(createCandle("60", p, p + 20, p - 50, p - 40, i));
        }

        // Advance 3 candles past cooldown
        Candle candle = createCandle("15", 22000, 22050, 21950, 21960, 26);
        strategy.onCandle(candle);
        strategy.onCandle(candle);
        strategy.onCandle(candle);
        // Condition is invalidated -> resets to FLAT
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
        assertEquals(1, strategy.getEntriesToday());

        // Setup bullish data for trade 2
        context.candles15m.clear();
        context.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 24000.0 + i * 50.0;
            context.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            context.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        // Trigger second trade
        Candle triggerCandle2 = createCandle("15", 25250, 25300, 25240, 25290, 25);
        context.addCandle15m(triggerCandle2);
        strategy.onCandle(triggerCandle2);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, strategy.getPosition());
        assertEquals(2, strategy.getEntriesToday());

        // Exit second trade via SL
        Tick slTick2 = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(25290 - 2000.0))
            .timestamp(Instant.now())
            .build();
        strategy.onTick(slTick2);

        // Invalidate condition to reset to FLAT
        context.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 100.0;
            context.addCandle1h(createCandle("60", p, p + 20, p - 50, p - 40, i));
        }
        strategy.onCandle(candle);
        strategy.onCandle(candle);
        strategy.onCandle(candle);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
        assertEquals(2, strategy.getEntriesToday());

        // Setup bullish data for trade 3
        context.candles15m.clear();
        context.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 26000.0 + i * 50.0;
            context.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            context.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        // Trigger 3rd trade -> should enter successfully because maxTradesPerDay is 0 (unlimited)
        emittedSignals.clear();
        Candle triggerCandle3 = createCandle("15", 27250, 27300, 27240, 27290, 25);
        context.addCandle15m(triggerCandle3);
        strategy.onCandle(triggerCandle3);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, strategy.getPosition());
        assertEquals(3, strategy.getEntriesToday());
        assertEquals(1, emittedSignals.size(), "3rd trade should be entered in unlimited mode");
    }

    @Test
    void testExplicitDailyTradeLimitEnforced() {
        IntradayTrendMomentumOptionSellingStrategy limitedStrategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_LIMITED", "ACC", "NIFTY_FUT",
            7, 3.0, 14, 50.0, 0.20, 70.0, 30.0, 0.0, 1, "15:20", 3, 2,
            List.of(), null, null, null, null
        );
        List<Signal> signals = new ArrayList<>();
        MockStrategyContext ctx = new MockStrategyContext(signals);
        limitedStrategy.init(ctx);

        // Trade 1
        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 50.0;
            ctx.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            ctx.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }
        Candle trigger1 = createCandle("15", 23250, 23300, 23240, 23290, 25);
        ctx.addCandle15m(trigger1);
        limitedStrategy.onCandle(trigger1);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, limitedStrategy.getPosition());
        assertEquals(1, limitedStrategy.getEntriesToday());

        // Exit trade 1
        Tick slTick = Tick.builder().symbol("NIFTY_FUT").ltp(BigDecimal.valueOf(23290 - 2000.0)).timestamp(Instant.now()).build();
        limitedStrategy.onTick(slTick);
        ctx.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 100.0;
            ctx.addCandle1h(createCandle("60", p, p + 20, p - 50, p - 40, i));
        }
        Candle breakCandle = createCandle("15", 22000, 22050, 21950, 21960, 26);
        limitedStrategy.onCandle(breakCandle);
        limitedStrategy.onCandle(breakCandle);
        limitedStrategy.onCandle(breakCandle);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, limitedStrategy.getPosition());

        // Trade 2
        ctx.candles15m.clear();
        ctx.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 24000.0 + i * 50.0;
            ctx.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            ctx.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }
        Candle trigger2 = createCandle("15", 25250, 25300, 25240, 25290, 25);
        ctx.addCandle15m(trigger2);
        limitedStrategy.onCandle(trigger2);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, limitedStrategy.getPosition());
        assertEquals(2, limitedStrategy.getEntriesToday());

        // Exit trade 2
        Tick slTick2 = Tick.builder().symbol("NIFTY_FUT").ltp(BigDecimal.valueOf(25290 - 2000.0)).timestamp(Instant.now()).build();
        limitedStrategy.onTick(slTick2);
        ctx.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 23000.0 - i * 100.0;
            ctx.addCandle1h(createCandle("60", p, p + 20, p - 50, p - 40, i));
        }
        limitedStrategy.onCandle(breakCandle);
        limitedStrategy.onCandle(breakCandle);
        limitedStrategy.onCandle(breakCandle);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, limitedStrategy.getPosition());

        // Attempt trade 3 -> blocked by maxTradesPerDay (2)
        signals.clear();
        ctx.candles15m.clear();
        ctx.candles1h.clear();
        for (int i = 0; i < 25; i++) {
            double p = 26000.0 + i * 50.0;
            ctx.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            ctx.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }
        Candle trigger3 = createCandle("15", 27250, 27300, 27240, 27290, 25);
        ctx.addCandle15m(trigger3);
        limitedStrategy.onCandle(trigger3);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, limitedStrategy.getPosition());
        assertTrue(signals.isEmpty(), "3rd trade must not be entered because daily trade limit (2) is reached");
    }

    @Test
    void testReEntry_WaitsForCooldownAndEntersWhenConditionValid() {
        // 1. Enter first trade (Bullish PE)
        testSuperTrendBullishAndRsiBullish_GeneratesPeShortSignal();
        assertEquals(1, strategy.getEntriesToday());
        emittedSignals.clear();

        // 2. Trigger SL -> WAIT_FOR_REENTRY
        double entrySpot = 23290;
        Tick slTick = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(entrySpot - 2000.0))
            .timestamp(Instant.now())
            .build();
        strategy.onTick(slTick);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.WAIT_FOR_REENTRY, strategy.getPosition());
        emittedSignals.clear();

        // 3. Candle 1 during cooldown (< 3)
        Candle c1 = createCandle("15", 23250, 23300, 23240, 23290, 26);
        context.addCandle15m(c1);
        strategy.onCandle(c1);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.WAIT_FOR_REENTRY, strategy.getPosition());
        assertTrue(emittedSignals.isEmpty());

        // 4. Candle 2 during cooldown (< 3)
        Candle c2 = createCandle("15", 23260, 23310, 23250, 23300, 27);
        context.addCandle15m(c2);
        strategy.onCandle(c2);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.WAIT_FOR_REENTRY, strategy.getPosition());
        assertTrue(emittedSignals.isEmpty());

        // 5. Price moves back up so premium drops back to entry level
        Tick recoverTick = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(entrySpot))
            .timestamp(Instant.now())
            .build();
        strategy.onTick(recoverTick);

        // 6. Candle 3 (cooldown elapsed = 3, condition valid, premium recovered) -> triggers RE-ENTRY
        Candle c3 = createCandle("15", 23280, 23330, 23270, 23320, 28);
        context.addCandle15m(c3);
        strategy.onCandle(c3);

        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, strategy.getPosition());
        assertEquals(2, strategy.getEntriesToday());
        assertEquals(1, emittedSignals.size());
        assertEquals(SignalType.ENTRY_SHORT, emittedSignals.get(0).signalType());
        assertTrue(emittedSignals.get(0).tag().contains("REENTRY"));
    }

    @Test
    void testBlackoutDay_DoesNotEnterTrade() {
        String today = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        IntradayTrendMomentumOptionSellingStrategy blackoutStrategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_BLACKOUT", "ACC", "NIFTY_FUT",
            7, 3.0, 14, 50.0, 0.20, 70.0, 30.0, 0.0, 1, "15:20", 3, 2,
            List.of(today), null, null, null, null
        );
        List<Signal> signals = new ArrayList<>();
        MockStrategyContext ctx = new MockStrategyContext(signals);
        blackoutStrategy.init(ctx);

        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 50.0;
            ctx.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            ctx.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        Candle triggerCandle = createCandle("15", 23250, 23300, 23240, 23290, 25);
        ctx.addCandle15m(triggerCandle);
        blackoutStrategy.onCandle(triggerCandle);

        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, blackoutStrategy.getPosition());
        assertTrue(signals.isEmpty(), "Blackout day must prevent opening new positions");
    }

    @Test
    void testProfitTargetExit_ClosesTradeWhenTargetHit() {
        IntradayTrendMomentumOptionSellingStrategy ptStrategy = new IntradayTrendMomentumOptionSellingStrategy(
            "ST_PT", "ACC", "NIFTY_FUT",
            7, 3.0, 14, 50.0, 0.20, 70.0, 30.0, 50.0, 1, "15:20", 3, 2,
            List.of(), null, null, null, null
        );
        List<Signal> signals = new ArrayList<>();
        MockStrategyContext ctx = new MockStrategyContext(signals);
        ptStrategy.init(ctx);

        for (int i = 0; i < 25; i++) {
            double p = 22000.0 + i * 50.0;
            ctx.addCandle15m(createCandle("15", p, p + 20, p - 10, p + 10, i));
            ctx.addCandle1h(createCandle("60", p, p + 40, p - 20, p + 30, i));
        }

        Candle triggerCandle = createCandle("15", 23250, 23300, 23240, 23290, 25);
        ctx.addCandle15m(triggerCandle);
        ptStrategy.onCandle(triggerCandle);
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.IN_TRADE, ptStrategy.getPosition());
        signals.clear();

        // Underlying moves up strongly in favor of PE seller, causing premium to decay > 50%
        Tick profitTick = Tick.builder()
            .symbol("NIFTY_FUT")
            .ltp(BigDecimal.valueOf(23290 + 3000.0))
            .timestamp(Instant.now())
            .build();
        ptStrategy.onTick(profitTick);

        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, ptStrategy.getPosition());
        assertEquals(1, signals.size());
        assertEquals(SignalType.EXIT_SHORT, signals.get(0).signalType());
        assertTrue(signals.get(0).tag().contains("PROFIT_TARGET"));
    }

    @Test
    void testMarketCloseEvent_ResetsDailyState() {
        testSuperTrendBullishAndRsiBullish_GeneratesPeShortSignal();
        assertEquals(1, strategy.getEntriesToday());

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_CLOSE));
        assertEquals(IntradayTrendMomentumOptionSellingStrategy.Position.FLAT, strategy.getPosition());
        assertEquals(0, strategy.getEntriesToday(), "Market close must reset entriesToday to 0");
    }

    private static Candle createCandle(String timeframe, double open, double high, double low, double close, int index) {
        return new Candle(
            "NIFTY_FUT",
            timeframe,
            Instant.ofEpochSecond(1700000000L + index * 900L),
            BigDecimal.valueOf(open),
            BigDecimal.valueOf(high),
            BigDecimal.valueOf(low),
            BigDecimal.valueOf(close),
            1000L
        );
    }

    // ========== Mock StrategyContext ==========

    private static class MockStrategyContext implements StrategyContext {
        private final List<Signal> signals;
        final List<Candle> candles15m = new ArrayList<>();
        final List<Candle> candles1h = new ArrayList<>();

        MockStrategyContext(List<Signal> signals) {
            this.signals = signals;
        }

        void addCandle15m(Candle candle) {
            candles15m.add(candle);
        }

        void addCandle1h(Candle candle) {
            candles1h.add(candle);
        }

        @Override
        public String getStrategyId() { return "TEST_STRATEGY"; }

        @Override
        public String getAssignedAccountId() { return "TEST_ACCOUNT"; }

        @Override
        public void emitSignal(Signal signal) {
            signals.add(signal);
        }

        @Override
        public Optional<Candle> getLastCandle(String symbol, String timeframe) {
            if ("15".equals(timeframe) && !candles15m.isEmpty()) {
                return Optional.of(candles15m.get(candles15m.size() - 1));
            }
            if ("60".equals(timeframe) && !candles1h.isEmpty()) {
                return Optional.of(candles1h.get(candles1h.size() - 1));
            }
            return Optional.empty();
        }

        @Override
        public List<Candle> getHistoricalCandles(String symbol, String timeframe, int count) {
            if ("15".equals(timeframe)) {
                return candles15m.size() > count
                    ? candles15m.subList(candles15m.size() - count, candles15m.size())
                    : new ArrayList<>(candles15m);
            }
            if ("60".equals(timeframe)) {
                return candles1h.size() > count
                    ? candles1h.subList(candles1h.size() - count, candles1h.size())
                    : new ArrayList<>(candles1h);
            }
            return List.of();
        }

        @Override
        public double[] getClosePrices(String symbol, String timeframe) {
            List<Candle> source = "15".equals(timeframe) ? candles15m : candles1h;
            return source.stream()
                .mapToDouble(c -> c.close().doubleValue())
                .toArray();
        }

        @Override
        public Instant now() {
            return Instant.now();
        }
    }
}
