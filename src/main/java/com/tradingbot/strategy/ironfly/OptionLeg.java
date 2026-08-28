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
     * Returns 0.0 if currentPrice or entryPrice is uninitialized/zero.
     */
    public double getDecayPercentage() {
        if (!isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0
            || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        BigDecimal decay = entryPrice.subtract(currentPrice)
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return decay.doubleValue();
    }

    /**
     * Calculates loss percentage for a short leg.
     * Loss = (currentPrice - entryPrice) / entryPrice * 100
     * Positive value means the short leg is losing money.
     * Returns 0.0 if currentPrice or entryPrice is uninitialized/zero.
     */
    public double getLossPercentage() {
        if (!isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0
            || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        BigDecimal loss = currentPrice.subtract(entryPrice)
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
        return Math.max(0.0, loss.doubleValue());
    }

    /**
     * Calculates profit percentage for a long leg.
     * Profit = (currentPrice - entryPrice) / entryPrice * 100
     * Positive value means the long leg is profitable.
     * Returns 0.0 if currentPrice or entryPrice is uninitialized/zero.
     */
    public double getProfitPercentage() {
        if (isShort || entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0
            || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        BigDecimal profit = currentPrice.subtract(entryPrice)
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
        if (entryPrice == null || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal entry = entryPrice;
        BigDecimal current = currentPrice;
        BigDecimal pnlPerUnit = isShort
            ? entry.subtract(current)
            : current.subtract(entry);
        return pnlPerUnit.multiply(BigDecimal.valueOf(lotSize));
    }
}
