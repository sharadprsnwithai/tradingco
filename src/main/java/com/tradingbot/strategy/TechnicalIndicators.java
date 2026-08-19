package com.tradingbot.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * High-performance, zero-allocation mathematical technical indicators
 * operating directly over primitive arrays.
 */
public final class TechnicalIndicators {

    private TechnicalIndicators() {}

    /**
     * Calculates Simple Moving Average (SMA) of the last N values in array.
     */
    public static double calculateSma(double[] values, int period) {
        if (values == null || values.length < period || period <= 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        int start = values.length - period;
        for (int i = start; i < values.length; i++) {
            sum += values[i];
        }
        return sum / period;
    }

    /**
     * Calculates Exponential Moving Average (EMA) of period N over values array.
     */
    public static double calculateEma(double[] values, int period) {
        if (values == null || values.length < period || period <= 0) {
            return Double.NaN;
        }
        double multiplier = 2.0 / (period + 1.0);
        // Seed EMA with initial SMA of the first 'period' elements
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += values[i];
        }
        ema /= period;

        // Apply EMA formula sequentially
        for (int i = period; i < values.length; i++) {
            ema = ((values[i] - ema) * multiplier) + ema;
        }
        return ema;
    }

    /**
     * Rounds a {@code double} value to 2 decimal places using half-up rounding.
     *
     * @param value the value to round
     * @return a {@link BigDecimal} rounded to 2 decimal places
     */
    public static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
