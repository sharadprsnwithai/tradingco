package com.trading.bot.strategy.model;

import com.trading.bot.broker.api.model.Exchange;

/** Underlying index target specifications for intraday trading. */
public enum TargetIndex {
  NIFTY(50, 1000, 65, Exchange.NSE, "NIFTY 50"),
  SENSEX(100, 2000, 20, Exchange.BSE, "SENSEX");

  private final int step;
  private final int hedgeDist;
  private final int lots;
  private final Exchange exchange;
  private final String symbol;

  TargetIndex(int step, int hedgeDist, int lots, Exchange exchange, String symbol) {
    this.step = step;
    this.hedgeDist = hedgeDist;
    this.lots = lots;
    this.exchange = exchange;
    this.symbol = symbol;
  }

  public int strikeStep() {
    return step;
  }

  public int hedgeDistance() {
    return hedgeDist;
  }

  public int lotSize() {
    return lots;
  }

  public Exchange spotExchange() {
    return exchange;
  }

  public String spotSymbol() {
    return symbol;
  }
}
