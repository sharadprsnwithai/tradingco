package com.tradingbot.backtest;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.impl.PdhPdlBreakoutStrategy;
import com.tradingbot.strategy.impl.VandeBharatStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void testBacktestReplayWithPdhBreakoutStrategy() {
        BacktestEngine backtestEngine = new BacktestEngine();
        PdhPdlBreakoutStrategy strategy = new PdhPdlBreakoutStrategy(
            "PDH_BACKTEST",
            "MOCK_ACCOUNT",
            "NSE:NIFTY",
            10
        );

        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        List<Candle> historicalCandles = new ArrayList<>();

        // Bar 1: 09:15 - Baseline setup (Close 24000 -> PDH ~ 24120, PDL ~ 23880)
        historicalCandles.add(new Candle("NSE:NIFTY", "5", t0, new BigDecimal("24000"), new BigDecimal("24050"), new BigDecimal("23950"), new BigDecimal("24000"), 5000L));

        // Bar 2: 09:20 - Long Breakout (Close 24150 > PDH 24120, SL: Low 24090, Target: 24150 + 2*(24150-24090=60) = 24270)
        historicalCandles.add(new Candle("NSE:NIFTY", "5", t0.plusSeconds(300), new BigDecimal("24050"), new BigDecimal("24160"), new BigDecimal("24090"), new BigDecimal("24150"), 12000L));

        // Bar 3: 09:25 - Target Hit (High 24280 >= 24270)
        historicalCandles.add(new Candle("NSE:NIFTY", "5", t0.plusSeconds(600), new BigDecimal("24160"), new BigDecimal("24280"), new BigDecimal("24140"), new BigDecimal("24275"), 8000L));

        BacktestResult result = backtestEngine.run(strategy, historicalCandles, new BigDecimal("100000.00"));

        assertNotNull(result);
        assertEquals("PDH_BACKTEST", result.strategyId());
        assertEquals(1, result.totalTrades());
        assertEquals(1, result.winningTrades());
        assertEquals(0, result.losingTrades());
        assertEquals(100.0, result.winRatePercent());
        assertTrue(result.netPnL().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(result.finalCapital().compareTo(result.initialCapital()) > 0);

        BacktestTrade trade = result.trades().get(0);
        assertEquals("NSE:NIFTY", trade.symbol());
        assertEquals("LONG", trade.direction());
        assertEquals(new BigDecimal("24150"), trade.entryPrice());
        assertEquals(new BigDecimal("24280"), trade.exitPrice());
        assertEquals(10, trade.quantity());
    }

    @Test
    void testBacktestReplayWithVandeBharatStrategy() {
        BacktestEngine backtestEngine = new BacktestEngine();
        VandeBharatStrategy strategy = new VandeBharatStrategy(
            "VB_BACKTEST",
            "MOCK_ACCOUNT",
            "NSE:RELIANCE",
            10
        );

        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        List<Candle> historicalCandles = new ArrayList<>();

        // Bar 1: 09:15 Seed candle (Close 3000 -> PDH 3030, PDL 2970)
        historicalCandles.add(new Candle("NSE:RELIANCE", "5", t0, new BigDecimal("3000"), new BigDecimal("3010"), new BigDecimal("2990"), new BigDecimal("3000"), 1000L));

        // Bar 2: 09:20 Breakout candle (Close 3040 > 3030, High 3050, Low 3015, Vol 5000)
        historicalCandles.add(new Candle("NSE:RELIANCE", "5", t0.plusSeconds(300), new BigDecimal("3020"), new BigDecimal("3050"), new BigDecimal("3015"), new BigDecimal("3040"), 5000L));

        // Bar 3: 09:25 Inside candle (High 3045 <= 3050, Low 3025 >= 3015, Vol 2000 <= 5000)
        historicalCandles.add(new Candle("NSE:RELIANCE", "5", t0.plusSeconds(600), new BigDecimal("3035"), new BigDecimal("3045"), new BigDecimal("3025"), new BigDecimal("3030"), 2000L));

        // Bar 4: 09:30 Entry Trigger candle (Close 3055 > 3045, Vol 4000 > 2000) -> Entry Long
        historicalCandles.add(new Candle("NSE:RELIANCE", "5", t0.plusSeconds(900), new BigDecimal("3030"), new BigDecimal("3060"), new BigDecimal("3028"), new BigDecimal("3055"), 4000L));

        // Bar 5: 09:35 Rally hitting 1:2 RR Target (3085) -> 50% partial exit, then drops triggering SL -> full exit
        historicalCandles.add(new Candle("NSE:RELIANCE", "5", t0.plusSeconds(1200), new BigDecimal("3055"), new BigDecimal("3090"), new BigDecimal("3040"), new BigDecimal("3045"), 7000L));

        BacktestResult result = backtestEngine.run(strategy, historicalCandles, new BigDecimal("100000.00"));

        assertNotNull(result);
        assertEquals("VB_BACKTEST", result.strategyId());
        assertTrue(result.totalTrades() >= 1);
        assertEquals(new BigDecimal("100000.00"), result.initialCapital());
    }
}
