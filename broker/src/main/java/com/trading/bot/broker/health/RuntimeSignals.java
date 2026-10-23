package com.trading.bot.broker.health;

import org.springframework.stereotype.Component;

/** Shared runtime signals tracking background component freshness and failure counters. */
@Component
public class RuntimeSignals {

  private volatile long lastPollerRunEpochSec;
  private volatile long lastReconcilerRunEpochSec;

  public long lastPollerRun() {
    return lastPollerRunEpochSec;
  }

  public void markPollerRun(long epochSec) {
    this.lastPollerRunEpochSec = epochSec;
  }

  public void markPollerRun() {
    this.lastPollerRunEpochSec = System.currentTimeMillis() / 1000L;
  }

  public long lastReconcilerRun() {
    return lastReconcilerRunEpochSec;
  }

  public void markReconcilerRun(long epochSec) {
    this.lastReconcilerRunEpochSec = epochSec;
  }

  public void markReconcilerRun() {
    this.lastReconcilerRunEpochSec = System.currentTimeMillis() / 1000L;
  }
}
