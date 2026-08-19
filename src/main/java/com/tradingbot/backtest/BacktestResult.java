package com.tradingbot.backtest;

import java.math.BigDecimal;
import java.util.List;

public record BacktestResult(
    String strategyId,
    BigDecimal initialCapital,
    BigDecimal finalCapital,
    BigDecimal netPnL,
    int totalTrades,
    int winningTrades,
    int losingTrades,
    double winRatePercent,
    BigDecimal grossProfit,
    BigDecimal grossLoss,
    double profitFactor,
    BigDecimal maxDrawdown,
    double maxDrawdownPercent,
    List<BacktestTrade> trades
) {}
