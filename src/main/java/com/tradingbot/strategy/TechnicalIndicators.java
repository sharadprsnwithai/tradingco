package com.tradingbot.strategy;

import com.tictactec.ta.lib.Core;
import com.tictactec.ta.lib.MInteger;
import com.tictactec.ta.lib.RetCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * High-performance technical indicators using TA-Lib for RSI and ATR,
 * with custom SuperTrend implementation (not available in TA-Lib).
 */
public final class TechnicalIndicators {

    private static final Core TA_LIB = new Core();

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
        double ema = 0.0;
        for (int i = 0; i < period; i++) {
            ema += values[i];
        }
        ema /= period;

        for (int i = period; i < values.length; i++) {
            ema = ((values[i] - ema) * multiplier) + ema;
        }
        return ema;
    }

    /**
     * Rounds a {@code double} value to 2 decimal places using half-up rounding.
     */
    public static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates Average True Range (ATR) using TA-Lib.
     *
     * @param high   array of high prices
     * @param low    array of low prices
     * @param close  array of close prices
     * @param period ATR lookback period (e.g. 7)
     * @return current ATR value, or NaN if insufficient data
     */
    public static double calculateAtr(double[] high, double[] low, double[] close, int period) {
        if (high == null || low == null || close == null
            || high.length < period + 1 || low.length < period + 1 || close.length < period + 1
            || period <= 0) {
            return Double.NaN;
        }

        int len = Math.min(high.length, Math.min(low.length, close.length));
        MInteger outBegIdx = new MInteger();
        MInteger outNBElement = new MInteger();
        double[] output = new double[len];

        RetCode retCode = TA_LIB.atr(0, len - 1, high, low, close, period, outBegIdx, outNBElement, output);
        if (retCode != RetCode.Success) return Double.NaN;

        int calculated = outNBElement.value;
        if (calculated <= 0) return Double.NaN;
        return output[calculated - 1];
    }

    /**
     * Calculates Relative Strength Index (RSI) using TA-Lib.
     *
     * @param close  array of close prices
     * @param period RSI lookback period (e.g. 14)
     * @return current RSI value (0-100), or NaN if insufficient data
     */
    public static double calculateRsi(double[] close, int period) {
        if (close == null || close.length < period + 1 || period <= 0) {
            return Double.NaN;
        }

        MInteger outBegIdx = new MInteger();
        MInteger outNBElement = new MInteger();
        double[] output = new double[close.length];

        RetCode retCode = TA_LIB.rsi(0, close.length - 1, close, period, outBegIdx, outNBElement, output);
        if (retCode != RetCode.Success) return Double.NaN;

        int calculated = outNBElement.value;
        if (calculated <= 0) return Double.NaN;
        return output[calculated - 1];
    }

    /**
     * Calculates SuperTrend indicator.
     * Returns a double array where:
     * - Positive value = Bullish (price above SuperTrend)
     * - Negative value = Bearish (price below SuperTrend)
     * - The absolute value is the SuperTrend line level.
     *
     * Uses TA-Lib ATR internally for the ATR calculation.
     *
     * @param high       array of high prices
     * @param low        array of low prices
     * @param close      array of close prices
     * @param atrPeriod  ATR lookback period (e.g. 7)
     * @param multiplier ATR multiplier (e.g. 3.0)
     * @return array of SuperTrend values (positive=bullish, negative=bearish), or NaN array if insufficient data
     */
    public static double[] calculateSuperTrend(double[] high, double[] low, double[] close,
                                                int atrPeriod, double multiplier) {
        int len = close.length;
        double[] result = new double[len];

        if (high == null || low == null || close == null || len < atrPeriod + 1) {
            java.util.Arrays.fill(result, Double.NaN);
            return result;
        }

        len = Math.min(len, Math.min(high.length, low.length));
        if (len < atrPeriod + 1) {
            java.util.Arrays.fill(result, Double.NaN);
            return result;
        }

        // Calculate True Range
        double[] tr = new double[len];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < len; i++) {
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        // Calculate ATR (Wilder's smoothing)
        double[] atr = new double[len];
        double sum = 0.0;
        for (int i = 0; i < atrPeriod; i++) {
            sum += tr[i];
        }
        atr[atrPeriod - 1] = sum / atrPeriod;
        for (int i = atrPeriod; i < len; i++) {
            atr[i] = (atr[i - 1] * (atrPeriod - 1) + tr[i]) / atrPeriod;
        }

        // Calculate Basic Upper and Lower Bands
        double[] basicUpperBand = new double[len];
        double[] basicLowerBand = new double[len];
        for (int i = atrPeriod - 1; i < len; i++) {
            double hl2 = (high[i] + low[i]) / 2.0;
            basicUpperBand[i] = hl2 + multiplier * atr[i];
            basicLowerBand[i] = hl2 - multiplier * atr[i];
        }

        // Calculate Final Upper and Lower Bands with path persistence
        double[] finalUpperBand = new double[len];
        double[] finalLowerBand = new double[len];
        double[] superTrend = new double[len];
        boolean[] isBullish = new boolean[len];

        int startIdx = atrPeriod - 1;
        finalUpperBand[startIdx] = basicUpperBand[startIdx];
        finalLowerBand[startIdx] = basicLowerBand[startIdx];

        double midpoint = (high[startIdx] + low[startIdx]) / 2.0;
        if (close[startIdx] <= midpoint) {
            superTrend[startIdx] = finalUpperBand[startIdx];
            isBullish[startIdx] = false;
        } else {
            superTrend[startIdx] = finalLowerBand[startIdx];
            isBullish[startIdx] = true;
        }

        for (int i = startIdx + 1; i < len; i++) {
            if (isBullish[i - 1] && finalLowerBand[i - 1] > basicLowerBand[i]) {
                finalLowerBand[i] = finalLowerBand[i - 1];
            } else {
                finalLowerBand[i] = basicLowerBand[i];
            }

            if (!isBullish[i - 1] && finalUpperBand[i - 1] < basicUpperBand[i]) {
                finalUpperBand[i] = finalUpperBand[i - 1];
            } else {
                finalUpperBand[i] = basicUpperBand[i];
            }

            if (!isBullish[i - 1]) {
                if (close[i] > finalUpperBand[i]) {
                    isBullish[i] = true;
                    superTrend[i] = finalLowerBand[i];
                } else {
                    isBullish[i] = false;
                    superTrend[i] = finalUpperBand[i];
                }
            } else {
                if (close[i] < finalLowerBand[i]) {
                    isBullish[i] = false;
                    superTrend[i] = finalUpperBand[i];
                } else {
                    isBullish[i] = true;
                    superTrend[i] = finalLowerBand[i];
                }
            }
        }

        for (int i = 0; i < startIdx; i++) {
            result[i] = Double.NaN;
        }

        for (int i = startIdx; i < len; i++) {
            result[i] = isBullish[i] ? Math.abs(superTrend[i]) : -Math.abs(superTrend[i]);
        }

        return result;
    }
}
