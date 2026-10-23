package com.trading.bot.broker.kite;

/** Lifecycle execution states of an order tracked by ExecutionTracker. */
public enum OrderState {
  SUBMITTED,
  OPEN,
  PARTIAL,
  FILLED,
  CANCELLED,
  REJECTED,
  EXPIRED,
  LOST,
  FAILED;

  /**
   * Returns true if this state is terminal and immutable.
   *
   * @return true if terminal
   */
  public boolean isTerminal() {
    return this == FILLED
        || this == CANCELLED
        || this == REJECTED
        || this == EXPIRED
        || this == LOST
        || this == FAILED;
  }
}
