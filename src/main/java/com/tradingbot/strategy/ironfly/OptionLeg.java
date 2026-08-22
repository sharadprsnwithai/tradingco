package com.tradingbot.strategy.ironfly;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Represents a single leg of an Iron Fly position.
 */
public record OptionLeg(
    String symbol,
    int strike,
    OptionType optionType,
    boolean isShort,
    BigDecimal entryPrice,
    BigDecimal currentPrice,
    double delta,
    int lotSize
) {
    /**
     * Calculates the decay percentage for a short leg.
     * Decay = (entryPrice - currentPrice) / entryPrice * 100
     * A higher value means more time decay has occurred.
     */
    public double getDecayPercentage() {
        if (!isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        BigDecimal decay = entryPrice.subtract(nullSafe(currentPrice))
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return decay.doubleValue();
    }

    /**
     * Calculates loss percentage for a short leg.
     * Loss = (currentPrice - entryPrice) / entryPrice * 100
     * Positive value means the short leg is losing money.
     */
    public double getLossPercentage() {
        if (!isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        BigDecimal loss = nullSafe(currentPrice).subtract(entryPrice)
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return Math.max(0.0, loss.doubleValue());
    }

    /**
     * Calculates profit percentage for a long leg.
     * Profit = (currentPrice - entryPrice) / entryPrice * 100
     * Positive value means the long leg is profitable.
     */
    public double getProfitPercentage() {
        if (isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        BigDecimal profit = nullSafe(currentPrice).subtract(entryPrice)
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return Math.max(0.0, profit.doubleValue());
    }

    /**
     * Calculates MTM P&L for this leg.
     * Short leg: profit when price drops (entryPrice - currentPrice) * lotSize
     * Long leg: profit when price rises (currentPrice - entryPrice) * lotSize
     */
    public BigDecimal getMtmPnl() {
        BigDecimal entry = nullSafe(entryPrice);
        BigDecimal current = nullSafe(currentPrice);
        BigDecimal pnlPerUnit = isShort
            ? entry.subtract(current)
            : current.subtract(entry);
        return pnlPerUnit.multiply(BigDecimal.valueOf(lotSize));
    }

    private static BigDecimal nullSafe(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }
}
