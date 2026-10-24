# Strategy Engine Flaw Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 4 verified flaws from `strategy_review.md` in the strategy engine: enable the continuous stop-loss monitoring loop, wire configured safety gates (VIX threshold, spread percentage, profit target decay), eliminate duplicate `roundToTick`, and prevent order tag collisions, while preserving the verified NIFTY lot size of 65.

**Architecture:** 
1. `TickUtils`: Centralize tick rounding into a shared utility in `com.trading.bot.strategy.engine` and eliminate verbatim duplicate methods.
2. `LiveStrategyGateway`: Replace `System.currentTimeMillis() % 1_000_000` with an atomic monotonic sequence + timestamp tag generator (<= 20 alphanumeric characters) and wire `StrategyProperties.maxSpreadPercentage()`.
3. `IntradayOptionStrategy`: Annotate `checkRisk()` with `@Scheduled(fixedDelay = 1000)` and `synchronized`, and enforce the `maxVixThreshold` circuit breaker before 09:45 and 10:00 entry checkpoints.
4. `PositionRiskManager`: Add decay profit-target checking via `StrategyProperties.profitTargetPercentage()` during active risk evaluation.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Gradle 9.8, AssertJ, Mockito, JUnit 5, SLF4J, Zerodha Kite Connect API.

**Spec:** [strategy_review.md](file:///D:/code/final_trading_bot/strategy_review.md)

## Global Constraints

- Java 25 preview features not required; strict adherence to PMD, Checkstyle, and SpotBugs rulesets.
- All code formatted via `./gradlew spotlessApply`.
- Tick rounding MUST align to ₹0.05 step (`BigDecimal` scale 2, `HALF_UP`).
- Kite order tags MUST NOT exceed 20 alphanumeric characters and MUST be collision-free.
- TargetIndex NIFTY lot size MUST remain 65 (verified against live Kite instrument master).

---

### Task 1: Centralize Tick Rounding in `TickUtils` (Fix 3)

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/TickUtils.java`
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java:484-492`
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java:343-351`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/TickUtilsTest.java`

**Interfaces:**
- Produces: `TickUtils.roundToTick(BigDecimal price) -> BigDecimal`

- [ ] **Step 1: Write the failing unit test for `TickUtils`**

Create `strategy/src/test/java/com/trading/bot/strategy/engine/TickUtilsTest.java`:
```java
package com.trading.bot.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TickUtilsTest {

  @Test
  void nullPriceReturnsZero() {
    assertThat(TickUtils.roundToTick(null)).isEqualTo(BigDecimal.ZERO);
  }

  @Test
  void roundsToNearestFivePaise() {
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.02)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.00));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.03)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.05));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.07)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.05));
    assertThat(TickUtils.roundToTick(BigDecimal.valueOf(100.08)))
        .isEqualByComparingTo(BigDecimal.valueOf(100.10));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.TickUtilsTest`
Expected: Compilation failure (cannot find symbol `TickUtils`).

- [ ] **Step 3: Implement `TickUtils` and replace duplicates in `IntradayOptionStrategy` and `PositionRiskManager`**

Create `strategy/src/main/java/com/trading/bot/strategy/engine/TickUtils.java`:
```java
package com.trading.bot.strategy.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Utility for price tick alignment and rounding. */
public final class TickUtils {

  private static final BigDecimal TICKS_PER_UNIT = BigDecimal.valueOf(20);

  private TickUtils() {}

  /**
   * Rounds a price to the nearest exchange tick (0.05) using HALF_UP rounding.
   *
   * @param price input price
   * @return tick-rounded price or ZERO if null
   */
  public static BigDecimal roundToTick(BigDecimal price) {
    if (price == null) {
      return BigDecimal.ZERO;
    }
    return price
        .multiply(TICKS_PER_UNIT)
        .setScale(0, RoundingMode.HALF_UP)
        .divide(TICKS_PER_UNIT, 2, RoundingMode.HALF_UP);
  }
}
```

In `IntradayOptionStrategy.java` and `PositionRiskManager.java`:
- Replace calls to private `roundToTick(...)` with `TickUtils.roundToTick(...)`.
- Delete the private static `roundToTick(BigDecimal price)` methods in both classes.

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew.bat :strategy:test`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add strategy/src/main/java/com/trading/bot/strategy/engine/TickUtils.java strategy/src/test/java/com/trading/bot/strategy/engine/TickUtilsTest.java strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java
git commit -m "refactor(strategy): centralize roundToTick in TickUtils"
```

---

### Task 2: Fix Order Tag Collisions & Wire Spread Gate in `LiveStrategyGateway` (Fix 4 & Fix 2 part 1)

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/gateway/LiveStrategyGateway.java:42-156`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/gateway/ExecutionGatewayTest.java`

**Interfaces:**
- Consumes: `StrategyProperties.maxSpreadPercentage()`, `TickUtils.roundToTick(...)`
- Produces: Collision-resistant alphanumeric order tags <= 20 chars (`generateOrderTag(String prefix)`)

- [ ] **Step 1: Write failing tests in `ExecutionGatewayTest.java` for unique tags and spread gate**

In `strategy/src/test/java/com/trading/bot/strategy/gateway/ExecutionGatewayTest.java`:
- Add test verifying multiple placed orders receive distinct tags.
- Add test verifying tags are <= 20 characters and alphanumeric.
- Add test verifying spread limit uses `props.maxSpreadPercentage()` and tick rounding.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.gateway.ExecutionGatewayTest`
Expected: Test fails if checking tag uniqueness or dynamic spread percentage.

- [ ] **Step 3: Implement unique order tag generator and dynamic spread in `LiveStrategyGateway`**

In `LiveStrategyGateway.java`:
- Add `private final AtomicLong tagCounter = new AtomicLong(0);`
- Add helper method:
```java
private String generateOrderTag(String prefix) {
  final long count = tagCounter.incrementAndGet() % 10_000L;
  final long timePart = System.currentTimeMillis() % 1_000_000_000L;
  final String tag = String.format(Locale.ROOT, "%s%09d%04d", prefix, timePart, count);
  return tag.length() > 20 ? tag.substring(0, 20) : tag;
}
```
- In `executeMarketWithSpreadGate`:
  - Calculate spread factor: `final double spreadPct = (props != null ? props.maxSpreadPercentage() : 1.5) / 100.0;`
  - Compute limit price:
    `side == TransactionType.BUY ? ltp.multiply(BigDecimal.valueOf(1.0 + spreadPct)) : ltp.multiply(BigDecimal.valueOf(1.0 - spreadPct));`
  - Round limit price to tick: `final BigDecimal limitPrice = TickUtils.roundToTick(rawLimitPrice);`
  - Use `generateOrderTag("strat")` for order tag.
- In `placeStopLossBuy`:
  - Use `generateOrderTag("sl")` for stop-loss order tag.

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.gateway.ExecutionGatewayTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add strategy/src/main/java/com/trading/bot/strategy/gateway/LiveStrategyGateway.java strategy/src/test/java/com/trading/bot/strategy/gateway/ExecutionGatewayTest.java
git commit -m "fix(strategy): make live gateway order tags collision-resistant and bind maxSpreadPercentage"
```

---

### Task 3: Enforce VIX Safety Gate in `IntradayOptionStrategy` (Fix 2 part 2)

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java:120-215`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java`

**Interfaces:**
- Consumes: `StrategyProperties.maxVixThreshold()`, `Broker.quotes(...)` with `NSE:INDIA VIX` (`264969L`)

- [ ] **Step 1: Write failing test in `IntradayOptionStrategyTest` for VIX circuit breaker**

Add test `vixAboveThresholdAbortsHedgeAndStraddleEntry()`:
- Mock broker quote for India VIX token `264969L` at 25.5 (exceeding default 24.0).
- Call `strategy.executeHedge()`.
- Verify zero buy orders placed and critical alert published with title containing "VIX Circuit Breaker Engaged".
- Verify state repository not saved with HEDGED.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.IntradayOptionStrategyTest.vixAboveThresholdAbortsHedgeAndStraddleEntry`
Expected: FAIL (orders are placed because VIX is not yet checked).

- [ ] **Step 3: Implement VIX check in `IntradayOptionStrategy`**

In `IntradayOptionStrategy.java`:
- Add constants:
```java
public static final long INDIA_VIX_TOKEN = 264969L;
public static final String INDIA_VIX_SYMBOL = "INDIA VIX";
```
- Add helper method:
```java
private boolean isVixWithinThreshold() {
  try {
    final QuoteRef vixRef =
        new QuoteRef(Exchange.NSE, INDIA_VIX_SYMBOL, new InstrumentToken(INDIA_VIX_TOKEN));
    final Map<InstrumentToken, Quote> quotes = broker.quotes(List.of(vixRef));
    final Quote q = quotes.get(vixRef.token());
    if (q != null && q.lastPrice() != null) {
      final double vix = q.lastPrice().doubleValue();
      if (vix > props.maxVixThreshold()) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("India VIX ({}) exceeds threshold ({}). Circuit breaker engaged.", vix, props.maxVixThreshold());
        }
        publisher.publish(
            Notification.of(
                Severity.CRITICAL,
                NotificationType.SYSTEM_ALERT,
                "VIX Circuit Breaker Engaged",
                String.format(
                    Locale.ROOT,
                    "India VIX is %.2f, exceeding max allowable threshold %.2f. Trading gated.",
                    vix,
                    props.maxVixThreshold())));
        return false;
      }
    }
  } catch (BrokerException e) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("Notice checking India VIX: {}", e.getMessage());
    }
  }
  return true;
}
```
- Call `if (!isVixWithinThreshold()) return;` at the beginning of `executeHedge()` and `executeStraddle()`.

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.IntradayOptionStrategyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java
git commit -m "feat(strategy): enforce India VIX circuit breaker before trade entry"
```

---

### Task 4: Enforce Profit-Target Decay in `PositionRiskManager` (Fix 2 part 3)

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java:104-302`
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/StrategyAlertService.java`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

**Interfaces:**
- Consumes: `StrategyProperties.profitTargetPercentage()`
- Produces: `StrategyAlertService.notifyProfitTargetHit(TradeLeg leg, BigDecimal exitPrice, ExecutionMode mode)`

- [ ] **Step 1: Write failing test in `PositionRiskManagerTest` for profit target decay exit**

Add test `shortLegDecayingPastProfitTargetClosesLegAndBooksProfit()`:
- Setup active short CE entered at ₹100.0 with 85% profit target (target exit price <= ₹15.0).
- Mock quote LTP at ₹12.0.
- Call `riskManager.evaluateRisk(record)`.
- Verify resting SL order cancelled, buy market executed to close leg, leg marked closed, and `notifyProfitTargetHit` or alert dispatched.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.PositionRiskManagerTest`
Expected: FAIL (profit target logic not yet evaluated).

- [ ] **Step 3: Implement profit target evaluation and alert notification**

In `StrategyAlertService.java`:
- Add `public void notifyProfitTargetHit(TradeLeg leg, BigDecimal exitPrice, ExecutionMode mode)`:
```java
public void notifyProfitTargetHit(TradeLeg leg, BigDecimal exitPrice, ExecutionMode mode) {
  final String modeBadge = "[" + mode.name() + "]";
  final String title =
      String.format(Locale.ROOT, "%s Profit Target Hit: %s", modeBadge, leg.symbol());

  final BigDecimal safeExit = exitPrice != null ? exitPrice : leg.exitPrice();
  final BigDecimal profit =
      leg.entryPrice().subtract(safeExit).multiply(BigDecimal.valueOf(leg.quantity()));

  final String body =
      String.format(
          Locale.ROOT,
          "• Leg Exited: %s%n"
              + "• Entry Price: ₹%s%n"
              + "• Exit Price: ₹%s (Profit Target Decay)%n"
              + "• Leg Profit: ₹%s%n"
              + "• Wing Hedges: Active until 15:00 IST",
          leg.symbol(),
          leg.entryPrice().toPlainString(),
          safeExit.toPlainString(),
          profit.toPlainString());

  publisher.publish(Notification.of(Severity.INFO, NotificationType.ORDER_SUBMITTED, title, body));
}
```

In `PositionRiskManager.java`:
- For open `shortCe`:
  - Calculate `profitTargetPrice = TickUtils.roundToTick(shortCe.entryPrice().multiply(BigDecimal.valueOf(1.0 - (props.profitTargetPercentage() / 100.0))))`.
  - If `ceLtp.compareTo(BigDecimal.ZERO) > 0 && ceLtp.compareTo(profitTargetPrice) <= 0`:
    - Cancel `shortCe.slOrderId()`.
    - Buy at market to close.
    - Set `shortCe` closed with exit price.
    - Notify `alertService.notifyProfitTargetHit(...)`.
- Apply identical logic for open `shortPe`.

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.PositionRiskManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java strategy/src/main/java/com/trading/bot/strategy/engine/StrategyAlertService.java strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java
git commit -m "feat(strategy): add profit target decay exit to position risk evaluation"
```

---

### Task 5: Enable Continuous Stop-Loss & Risk Monitoring Loop (Fix 1)

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java:303-320`
- Modify: `strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java`

**Interfaces:**
- Produces: `@Scheduled(fixedDelay = 1000) public synchronized void checkRisk()`

- [ ] **Step 1: Write unit tests in `IntradayOptionStrategyTest` for `checkRisk`**

Add tests:
- `checkRiskEvaluatesWhenStraddleIsActive()`: verifies that when state is `STRADDLE_ACTIVE`, `riskManager.evaluateRisk(...)` is called.
- `checkRiskEvaluatesWhenOneLegStopped()`: verifies that when state is `ONE_LEG_STOPPED`, `riskManager.evaluateRisk(...)` is called.
- `checkRiskSkipsWhenIdleOrCompleted()`: verifies `riskManager.evaluateRisk(...)` is never called when state is IDLE or COMPLETED.

- [ ] **Step 2: Run test to verify initial state**

Run: `.\gradlew.bat :strategy:test --tests com.trading.bot.strategy.engine.IntradayOptionStrategyTest`
Expected: Passes for direct calls, but verifies behavior.

- [ ] **Step 3: Add `@Scheduled(fixedDelay = 1000)` and `synchronized` to `checkRisk()`**

In `IntradayOptionStrategy.java`:
```java
  /** Checkpoint 3: Continuous 1-second Risk & Stop-Loss Monitoring Loop. */
  @Scheduled(fixedDelay = 1000)
  @SuppressWarnings(AVOID_GENERIC_EXC)
  public synchronized void checkRisk() {
    final LocalDate today = LocalDate.now(clock);
    try {
      final DailyTradeRecord record = stateRepository.load(today).orElse(null);
      if (record != null
          && (record.state() == StrategyState.STRADDLE_ACTIVE
              || record.state() == StrategyState.ONE_LEG_STOPPED)) {
        riskManager.evaluateRisk(record);
      }
    } catch (Exception e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Error during risk evaluation loop: {}", e.getMessage(), e);
      }
    }
  }
```

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew.bat :strategy:test`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java
git commit -m "fix(strategy): activate continuous stop-loss monitoring loop with @Scheduled"
```

---

### Task 6: Full Verification and Quality Gates

**Files:**
- Entire repository

- [ ] **Step 1: Run full test suite**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL with 0 failures across all modules.

- [ ] **Step 2: Run code quality checks (Spotless, Checkstyle, PMD, SpotBugs)**

Run: `.\gradlew.bat check`
Expected: BUILD SUCCESSFUL with no lint, style, or static analysis violations.

- [ ] **Step 3: Update `strategy_review.md` with verification & resolution log**

Document the resolution of Flaws 1-4 and justification for keeping Flaw 5 unchanged.
