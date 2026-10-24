package com.trading.bot.strategy.config;

import java.time.LocalDate;

public record ForecastRange(
    String symbol,
    java.math.BigDecimal median,
    java.math.BigDecimal range80Low,
    java.math.BigDecimal range80High,
    java.math.BigDecimal range95Low,
    java.math.BigDecimal range95High,
    double probInRange,
    double expectedVol,
    LocalDate generatedAt,
    LocalDate periodFrom,
    LocalDate periodTo) {}
