package com.trading.bot.strategy.model;

/**
 * Encapsulates computed ATM strike and symmetric 20-strike OTM hedge strikes.
 *
 * @param atmStrike center straddle strike
 * @param otmCeStrike deep out-of-the-money call hedge strike
 * @param otmPeStrike deep out-of-the-money put hedge strike
 */
public record StrikeSelection(int atmStrike, int otmCeStrike, int otmPeStrike) {}
