# Tuning Java Backtest Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Tune strategy parameters and improve simulation realism to understand why the Java engine differs from the theoretical Python backtest.

**Architecture:** 
- Modify `BacktestBrokerStub` in `BacktestEngine.java` to model more "trending/sideways" market behavior (less high-frequency noise).
- Tune `StrategyProperties` in `BacktestRunnerTest.java` (e.g., adjust SL percentage, trail to breakeven parameters).

## Tasks

### Task 1: Refine Backtest Simulation Model

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/BacktestEngine.java`

- [ ] **Step 1: Reduce high-frequency noise in `BacktestBrokerStub.quotes()` to simulate a more stable market where theta decay is captured**
- [ ] **Step 2: Commit**

### Task 2: Parameter Tuning in Backtest Runner

**Files:**
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/BacktestRunnerTest.java`

- [ ] **Step 1: Adjust `StrategyProperties` for more aggressive profit-taking/breakeven trailing**
- [ ] **Step 2: Re-run `BacktestRunnerTest` and report results**
- [ ] **Step 3: Commit**
