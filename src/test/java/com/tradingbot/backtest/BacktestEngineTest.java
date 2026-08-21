package com.tradingbot.backtest;

import com.tradingbot.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void testBacktestEngineWithNoTrades() {
        BacktestEngine backtestEngine = new BacktestEngine();

        // Single candle that won't trigger any strategy entry
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        List<Candle> candles = List.of(
            new Candle("NSE:RELIANCE", "5", t0,
                new BigDecimal("2500"), new BigDecimal("2510"),
                new BigDecimal("2490"), new BigDecimal("2500"), 1000L)
        );

        BacktestResult result = backtestEngine.run(
            new MockStrategy("MOCK_STRATEGY_01", "MOCK_ACCOUNT", "NSE:RELIANCE"),
            candles,
            new BigDecimal("100000.00")
        );

        assertNotNull(result);
        assertEquals("MOCK_STRATEGY_01", result.strategyId());
        assertEquals(0, result.totalTrades());
        assertEquals(new BigDecimal("100000.00"), result.initialCapital());
        assertEquals(new BigDecimal("100000.00"), result.finalCapital());
    }
}
