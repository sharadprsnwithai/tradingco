package com.trading.bot.broker.kite;

/** Distinguishes read queries from state-altering mutations. */
public enum Mutability {
  READ,
  MUTATION
}
