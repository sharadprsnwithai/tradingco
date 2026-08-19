package com.tradingbot.strategy.impl;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PdhPdlBreakoutStrategyTest {

    private PdhPdlBreakoutStrategy strategy;
    private List<Signal> emittedSignals;

    @BeforeEach
    void setUp() {
        emittedSignals = new ArrayList<>();
        strategy = new PdhPdlBreakoutStrategy("PDH_TEST", "SHOONYA_01", "NSE:NIFTY", 25);

        StrategyContext context = new StrategyContext() {
            @Override public String getStrategyId() { return "PDH_TEST"; }
            @Override public String getAssignedAccountId() { return "SHOONYA_01"; }
            @Override public void emitSignal(Signal signal) { emittedSignals.add(signal); }
            @Override public Optional<Candle> getLastCandle(String symbol, String timeframe) { return Optional.empty(); }
            @Override public List<Candle> getHistoricalCandles(String symbol, String timeframe, int count) { return Collections.emptyList(); }
            @Override public double[] getClosePrices(String symbol, String timeframe) { return new double[0]; }
            @Override public Instant now() { return Instant.now(); }
        };

        strategy.init(context);
    }

    @Test
    void testPdhLongBreakoutAndTargetHit() {
        PdhPdlBreakoutStrategy.SymbolState state = strategy.getState("NSE:NIFTY");
        state.pdh = new BigDecimal("24500.00");
        state.pdl = new BigDecimal("24300.00");

        Instant t1 = Instant.parse("2024-12-18T09:20:00Z");
        // Candle breaks above PDH
        Candle breakoutCandle = new Candle("NSE:NIFTY", "5", t1, new BigDecimal("24480"), new BigDecimal("24530"), new BigDecimal("24470"), new BigDecimal("24520"), 10000L);
        strategy.onCandle(breakoutCandle);

        assertTrue(state.inTrade);
        assertTrue(state.isLong);
        assertEquals(1, emittedSignals.size());
        Signal entry = emittedSignals.get(0);
        assertEquals(SignalType.ENTRY_LONG, entry.signalType());
        assertEquals(new BigDecimal("24520"), entry.price());
        assertEquals(new BigDecimal("24470"), entry.triggerPrice()); // SL at candle low

        // Target = 24520 + 2 * (24520 - 24470 = 50) = 24620
        assertEquals(new BigDecimal("24620"), state.target);

        // Tick hits target 24625
        strategy.onTick(Tick.builder()
            .symbol("NSE:NIFTY")
            .ltp(new BigDecimal("24625.00"))
            .timestamp(t1.plusSeconds(30))
            .build());

        assertEquals(2, emittedSignals.size());
        Signal exit = emittedSignals.get(1);
        assertEquals(SignalType.EXIT_LONG, exit.signalType());
        assertEquals("PDH_EXIT_TARGET_HIT_1:2", exit.tag());
        assertFalse(state.inTrade);
    }
}
