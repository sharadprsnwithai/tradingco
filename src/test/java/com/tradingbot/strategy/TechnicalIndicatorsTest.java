package com.tradingbot.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TechnicalIndicators - ATR, RSI, and SuperTrend calculations.
 */
class TechnicalIndicatorsTest {

    @Test
    void testCalculateAtr_basicCase() {
        double[] high = {10.0, 11.0, 12.0, 11.5, 13.0, 12.5, 14.0, 13.5, 15.0, 14.5, 16.0};
        double[] low = {9.0, 10.0, 11.0, 10.5, 12.0, 11.5, 13.0, 12.5, 14.0, 13.5, 15.0};
        double[] close = {9.5, 10.5, 11.5, 11.0, 12.5, 12.0, 13.5, 13.0, 14.5, 14.0, 15.5};

        double atr = TechnicalIndicators.calculateAtr(high, low, close, 7);
        assertFalse(Double.isNaN(atr), "ATR should be calculated");
        assertTrue(atr > 0, "ATR should be positive");
    }

    @Test
    void testCalculateAtr_insufficientData() {
        double[] high = {10.0, 11.0};
        double[] low = {9.0, 10.0};
        double[] close = {9.5, 10.5};

        double atr = TechnicalIndicators.calculateAtr(high, low, close, 7);
        assertTrue(Double.isNaN(atr), "ATR should be NaN with insufficient data");
    }

    @Test
    void testCalculateAtr_nullInput() {
        double atr = TechnicalIndicators.calculateAtr(null, null, null, 7);
        assertTrue(Double.isNaN(atr), "ATR should be NaN with null input");
    }

    @Test
    void testCalculateRsi_uptrend() {
        // Strong uptrend: RSI should be high
        double[] close = {100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115};
        double rsi = TechnicalIndicators.calculateRsi(close, 14);
        assertFalse(Double.isNaN(rsi), "RSI should be calculated");
        assertTrue(rsi > 70, "RSI in uptrend should be > 70, got: " + rsi);
    }

    @Test
    void testCalculateRsi_downtrend() {
        // Strong downtrend: RSI should be low
        double[] close = {115, 114, 113, 112, 111, 110, 109, 108, 107, 106, 105, 104, 103, 102, 101, 100};
        double rsi = TechnicalIndicators.calculateRsi(close, 14);
        assertFalse(Double.isNaN(rsi), "RSI should be calculated");
        assertTrue(rsi < 30, "RSI in downtrend should be < 30, got: " + rsi);
    }

    @Test
    void testCalculateRsi_neutral() {
        // Mixed data: RSI should be around 50
        double[] close = {100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101};
        double rsi = TechnicalIndicators.calculateRsi(close, 14);
        assertFalse(Double.isNaN(rsi), "RSI should be calculated");
        assertTrue(rsi > 40 && rsi < 60, "RSI in neutral should be ~50, got: " + rsi);
    }

    @Test
    void testCalculateRsi_flatline() {
        // Flatline data: zero gain, zero loss -> TA-Lib returns 0.0
        double[] close = {100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100};
        double rsi = TechnicalIndicators.calculateRsi(close, 14);
        assertFalse(Double.isNaN(rsi), "RSI should be calculated");
        assertEquals(0.0, rsi, 0.001, "RSI on flatline data should be 0.0 (TA-Lib convention)");
    }

    @Test
    void testCalculateRsi_insufficientData() {
        double[] close = {100, 101, 102};
        double rsi = TechnicalIndicators.calculateRsi(close, 14);
        assertTrue(Double.isNaN(rsi), "RSI should be NaN with insufficient data");
    }

    @Test
    void testCalculateSuperTrend_uptrend() {
        // Generate uptrend data
        int len = 30;
        double[] high = new double[len];
        double[] low = new double[len];
        double[] close = new double[len];

        for (int i = 0; i < len; i++) {
            close[i] = 100 + i * 0.5;
            high[i] = close[i] + 1.0;
            low[i] = close[i] - 1.0;
        }

        double[] st = TechnicalIndicators.calculateSuperTrend(high, low, close, 7, 3.0);
        assertNotNull(st, "SuperTrend should not be null");
        assertEquals(len, st.length, "SuperTrend array length should match input");

        // In uptrend, last SuperTrend should be positive (bullish)
        double lastST = st[st.length - 1];
        assertFalse(Double.isNaN(lastST), "Last SuperTrend should not be NaN");
        assertTrue(lastST > 0, "SuperTrend in uptrend should be positive (bullish), got: " + lastST);
    }

    @Test
    void testCalculateSuperTrend_downtrend() {
        // Generate downtrend data
        int len = 30;
        double[] high = new double[len];
        double[] low = new double[len];
        double[] close = new double[len];

        for (int i = 0; i < len; i++) {
            close[i] = 130 - i * 0.5;
            high[i] = close[i] + 1.0;
            low[i] = close[i] - 1.0;
        }

        double[] st = TechnicalIndicators.calculateSuperTrend(high, low, close, 7, 3.0);
        assertNotNull(st, "SuperTrend should not be null");

        // In downtrend, last SuperTrend should be negative (bearish)
        double lastST = st[st.length - 1];
        assertFalse(Double.isNaN(lastST), "Last SuperTrend should not be NaN");
        assertTrue(lastST < 0, "SuperTrend in downtrend should be negative (bearish), got: " + lastST);
    }

    @Test
    void testCalculateSuperTrend_insufficientData() {
        double[] high = {10.0, 11.0, 12.0};
        double[] low = {9.0, 10.0, 11.0};
        double[] close = {9.5, 10.5, 11.5};

        double[] st = TechnicalIndicators.calculateSuperTrend(high, low, close, 7, 3.0);
        assertNotNull(st, "SuperTrend array should not be null");
        assertTrue(Double.isNaN(st[0]), "SuperTrend should be NaN with insufficient data");
    }

    @Test
    void testCalculateAtr_constantRange() {
        // Constant range of 2 points
        double[] high = {12.0, 12.0, 12.0, 12.0, 12.0, 12.0, 12.0, 12.0};
        double[] low = {10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0};
        double[] close = {11.0, 11.0, 11.0, 11.0, 11.0, 11.0, 11.0, 11.0};

        double atr = TechnicalIndicators.calculateAtr(high, low, close, 7);
        // With constant range and no gaps, ATR should be exactly 2.0
        assertEquals(2.0, atr, 0.001, "ATR with constant range should be 2.0");
    }

    @Test
    void testRound() {
        assertEquals("1.23", TechnicalIndicators.round(1.234).toString());
        assertEquals("1.24", TechnicalIndicators.round(1.235).toString());
        assertEquals("1.23", TechnicalIndicators.round(1.23).toString());
    }
}
