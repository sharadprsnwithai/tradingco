package com.trading.bot.strategy.model;

/** High-level lifecycle state of the intraday option selling strategy for the trading day. */
public enum StrategyState {
  IDLE,
  HEDGED,
  STRADDLE_ACTIVE,
  ONE_LEG_STOPPED,
  COMPLETED,
  ABORTED
}
