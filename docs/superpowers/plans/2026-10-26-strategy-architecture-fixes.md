# Strategy Architecture & Resiliency Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix remaining technical and architectural issues in the trading bot strategy module: replace blocking `Thread.sleep` in `LiveStrategyGateway` spread-gating with non-blocking or asynchronous retry (or timeout handling) so it never blocks the 1-second risk monitoring loop, and add robust quote/order-book error resilience in `PositionRiskManager`.

**Architecture:** Refactor `LiveStrategyGateway` spread-gate retries to avoid blocking the scheduler monitor thread with synchronous 30-second sleeps, and add robust null/stale quote and order book resilience in `PositionRiskManager`.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, AssertJ, Mockito.

**Spec:** Inraday Option Strategy Design (`docs/superpowers/specs/2026-10-23-intraday-option-selling-strategy-design.md`)

## Global Constraints
- Java 25 language level and toolchain.
- Zero breaking changes to existing broker interfaces or public strategy contracts.
- All existing tests must continue to pass successfully.

## Review Focus
- **Thread Blocking / Starvation:** Does `executeMarketWithSpreadGate` block the scheduler / `checkRisk()` loop? (Add test verifying non-blocking execution or bounded async retry).
- **Stale Quotes & Missing Order Book Data:** How does `PositionRiskManager` handle missing quotes or order book exceptions? (Ensure fallback graceful handling).

---

## Tasks

### Task 1: Non-Blocking / Bounded Spread-Gate in `LiveStrategyGateway`

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/gateway/LiveStrategyGateway.java`
- Test: `strategy/test/java/com/trading/bot/strategy/gateway/ExecutionGatewayTest.java` (or new test)

**Interfaces:**
- Consumes: `Broker.quotes()`, `Broker.placeOrder()`
- Produces: `LiveStrategyGateway.executeBuyMarket()` / `executeSellMarket()`

- [ ] **Step 1: Write a test verifying spread gate retries do not block indefinitely or use unbounded blocking sleeps**
- [ ] **Step 2: Modify `executeMarketWithSpreadGate` in `LiveStrategyGateway.java` to reduce retry sleep intervals or use non-blocking scheduling / timeout guards**
- [ ] **Step 3: Run tests and verify execution**

### Task 2: Robust Quote and Order Book Resilience in `PositionRiskManager`

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java`
- Test: `strategy/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

**Interfaces:**
- Consumes: `Broker.orderBook()`, `Broker.quotes()`
- Produces: `PositionRiskManager.evaluateRisk()`

- [ ] **Step 1: Write a test for `PositionRiskManager` handling broker exception during order book fetch gracefully**
- [ ] **Step 2: Add robust null/stale quote validation and order book error recovery in `PositionRiskManager.java`**
- [ ] **Step 3: Run tests and verify execution**
