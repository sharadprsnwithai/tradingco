# Low-Turnover Software Stop-Loss Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `use-exchange-sl` configuration property (defaulting to `false` or toggleable) to eliminate high-turnover resting exchange stop-loss order placements and mid-day modifications, relying purely on software-driven quote monitoring via `PositionRiskManager`.

**Architecture:** 
- Add `useExchangeSl` property to `StrategyProperties`.
- Conditionally place and modify exchange stop-loss orders in `IntradayOptionStrategy` and `PositionRiskManager` only when `useExchangeSl` is `true`. When `false`, rely entirely on quote polling and market exit orders.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, AssertJ, Mockito.

**Spec:** Low-Turnover Optimization Spec (reducing execution count from ~12 to ~6 per day).

## Global Constraints
- Java 25 language level.
- Backward compatible: `useExchangeSl` defaults to `false` (low-turnover software mode) to save transaction costs.
- All existing tests must pass successfully.

## Review Focus
- **Order Turnover Reduction:** Verify that when `useExchangeSl = false`, no `placeStopLossBuy` or `modifyStopLoss` calls are made to the gateway.
- **Risk Management Accuracy:** Verify that `PositionRiskManager` correctly triggers market exits and trailing breakeven purely based on live quote polling when exchange SL is disabled.

---

## Tasks

### Task 1: Add Configuration Property for Exchange SL

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/config/StrategyProperties.java`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/config/StrategyPropertiesTest.java`

- [ ] **Step 1: Add `boolean useExchangeSl` to `StrategyProperties`**
- [ ] **Step 2: Update tests and verify properties binding**

### Task 2: Conditional Exchange SL Placement in Strategy Entry

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java`

- [ ] **Step 1: Check `props.useExchangeSl()` in `executeStraddle()` before calling `gateway.placeStopLossBuy()`**
- [ ] **Step 2: Verify tests pass with `useExchangeSl = false`**

### Task 3: Conditional Exchange SL Modification in Position Risk Manager

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

- [ ] **Step 1: Check `props.useExchangeSl()` before calling `gateway.modifyStopLoss()` during breakeven trailing**
- [ ] **Step 2: Verify risk evaluation works correctly in software-only mode**
