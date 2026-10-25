# Portfolio-Level Profit Target Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Implement a portfolio-level profit target (e.g., locking in profits when total short straddle premium decays by 40% or more) to protect winning days from late-afternoon Nifty/Sensex trend reversals.

**Architecture:**
- Add `portfolioProfitTargetPercentage` to `StrategyProperties` (defaulting to 40.0% premium decay, or optional).
- In `PositionRiskManager.evaluateRisk()`, calculate total combined entry premium vs. current LTP of open short legs, and if total decay exceeds the portfolio target, square off both short legs immediately.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, AssertJ, Mockito.

## Tasks

### Task 1: Add Portfolio Profit Target Property
- Modify: `strategy/src/main/java/com/trading/bot/strategy/config/StrategyProperties.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/config/StrategyPropertiesTest.java`

### Task 2: Implement Portfolio Profit Target Evaluation in Risk Manager
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java`
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/StrategyAlertService.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`
