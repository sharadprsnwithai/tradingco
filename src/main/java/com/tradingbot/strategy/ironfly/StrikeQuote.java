package com.tradingbot.strategy.ironfly;

import java.math.BigDecimal;

/**
 * Quote data for a single option strike in the option chain.
 */
public record StrikeQuote(
    int strike,
    OptionType optionType,
    BigDecimal ltp,
    BigDecimal bid,
    BigDecimal ask,
    long openInterest,
    long volume,
    double delta,
    double gamma,
    double theta,
    double vega
) {}
