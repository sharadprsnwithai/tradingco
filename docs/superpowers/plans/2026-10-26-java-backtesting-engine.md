# Java Backtesting Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Java backtesting engine in the `:strategy` module to simulate intraday strategy performance using historical data.

**Architecture:**
- Create a `BacktestStrategyGateway` that implements `StrategyExecutionGateway`.
- Implement a `BacktestEngine` that feeds historical candle data to the `IntradayOptionStrategy` at scheduled checkpoints.
- Use existing `Broker` interface to mock/re-play market data.

**Tech Stack:** Java 25, JUnit 5, Mockito.

**Spec:** Derived from design doc `docs/superpowers/specs/2026-10-23-intraday-option-selling-strategy-design.md`.

## Global Constraints
- Java 25 toolchain.
- Reuse `IntradayOptionStrategy` and `PositionRiskManager` without modification.
- Simulation must maintain deterministic results based on input data.

## Review Focus
- **Time/Data Alignment**: Does simulated time correctly align with the `IntradayOptionStrategy` checkpoint logic?
- **Fill Simulation**: Are market orders correctly simulated against candle OHLV?

---

## Tasks

### Task 1: Backtest Execution Gateway

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/gateway/BacktestStrategyGateway.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/gateway/BacktestStrategyGatewayTest.java`

- [ ] **Step 1: Define `BacktestStrategyGateway` implementing `StrategyExecutionGateway`**
- [ ] **Step 2: Implement simulated fills against `HistoricalCandle` data**
- [ ] **Step 3: Test gateway simulation logic**

### Task 2: Backtest Orchestration Engine

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/BacktestEngine.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/BacktestEngineTest.java`

- [ ] **Step 1: Create `BacktestEngine` to load data and drive `IntradayOptionStrategy` checkpoints**
- [ ] **Step 2: Implement historical data loader to feed candles into `IntradayOptionStrategy`**
- [ ] **Step 3: Test engine driving strategy lifecycle**

### Task 3: P&L Report Generator

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/BacktestReporter.java`

- [ ] **Step 1: Implement P&L reporting to aggregate results from daily records**
- [ ] **Step 2: Add utility to run backtest over a 3-month range**
