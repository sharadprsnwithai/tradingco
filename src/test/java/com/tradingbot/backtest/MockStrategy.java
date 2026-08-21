package com.tradingbot.backtest;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Tick;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyContext;
import com.tradingbot.strategy.ScheduledEvent;

import java.util.List;

/**
 * Minimal mock strategy for testing the BacktestEngine without strategy logic dependencies.
 */
class MockStrategy implements Strategy {

    private final String strategyId;
    private final String accountId;
    private final String symbol;

    MockStrategy(String strategyId, String accountId, String symbol) {
        this.strategyId = strategyId;
        this.accountId = accountId;
        this.symbol = symbol;
    }

    @Override
    public String getStrategyId() { return strategyId; }

    @Override
    public String getAssignedAccountId() { return accountId; }

    @Override
    public List<String> getSubscribedSymbols() { return List.of(symbol); }

    @Override
    public void init(StrategyContext context) {}

    @Override
    public void onTick(Tick tick) {}

    @Override
    public void onCandle(Candle candle) {}

    @Override
    public void onSchedule(ScheduledEvent event) {}

    @Override
    public void destroy() {}

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public void setEnabled(boolean enabled) {}
}
