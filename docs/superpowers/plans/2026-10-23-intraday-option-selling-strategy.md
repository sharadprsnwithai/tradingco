# Intraday Option Selling Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `:strategy` module for automated systematic 0/1 DTE intraday option selling on NIFTY (Mon/Tue) and SENSEX (Wed/Thu) with 09:45 IST OTM hedges, 10:00 IST ATM short straddle, independent 30% stop-losses, and mandatory 15:00 IST square-off.

**Architecture:** A dedicated Gradle subproject `:strategy` consuming the `Broker` and `NotificationPublisher` ports. Provides dual execution gateways (`PAPER` vs `LIVE`), dynamic strike resolution from live index spot, persistence for crash resilience, and an independent leg risk manager (Option A).

**Tech Stack:** Java 25, Spring Boot 4.1.x, Gradle 9 (Kotlin DSL), Jackson 3 (`tools.jackson`), JUnit 5 + AssertJ + Mockito. Quality gates: Spotless, PMD, Checkstyle, SpotBugs.

**Spec:** `docs/superpowers/specs/2026-10-23-intraday-option-selling-strategy-design.md`

## Global Constraints

- Java 25 toolchain; Gradle 9 Kotlin DSL; multi-module build with root `build.gradle.kts`.
- Strictly decouple `:strategy` from internal Kite HTTP adapter classes; consume only `com.trading.bot.broker.api.Broker` and `com.trading.bot.notifications.api.NotificationPublisher`.
- All timestamps and schedules in `ZoneId.of("Asia/Kolkata")`.
- Weekly schedule: Mon/Tue NIFTY (Tue expiry), Wed/Thu SENSEX (Thu expiry), Fri NIFTY (next Tue expiry).
- Schedule checkpoints: 09:45 IST (buy 20-strike OTM hedges), 10:00 IST (sell ATM CE & PE), 15:00 IST (square-off all open legs).
- Stop-loss rule: 30% loss on entry premium per short leg. In LIVE mode, protective exchange SL orders are placed at 10:00 AM immediately after straddle entry; when one leg executes, the engine modifies the surviving leg's exchange SL order to its entry price (breakeven). In PAPER mode, this lifecycle is simulated in memory.
- Configurable environment properties in `.env` with default values: `STRATEGY_EXECUTION_MODE=PAPER`, `STRATEGY_LOTS=2`, `STRATEGY_SL_PERCENTAGE=30.0`, `STRATEGY_TRAIL_TO_BREAKEVEN=true`.
- Quality gates wired into `check`; `./gradlew build` must stay green after every task.

## Review Focus

1. **Midpoint Strike Boundary Rounding:** When NIFTY spot is on the exact midpoint boundary (e.g. 24,975.00), round deterministically to the upper strike (25,000) rather than floating-point drift. Tested in Task 3.
2. **Hedge Failure Safety Interlock & Straddle Rollback:** If 09:45 hedge buying is rejected or fails, the 10:00 short straddle MUST abort to prevent naked exposure. If CE sells but PE rejects, CE must immediately rollback/unwind. Tested in Task 7.
3. **Both Short Legs Stopped Simultaneously:** In volatile sessions where both CE and PE cross 30% SL, both close cleanly without double-exit attempts or race conditions. Tested in Task 6.
4. **Mid-Day Crash Recovery & De-duplication:** On container restart at 11:30 IST with an active trade, re-adopt state from `data/strategy_state.json` without re-entering hedges or straddles. Tested in Task 4.
5. **Post-Market Square-Off Catch-Up:** If restarted after 15:00 IST with unfinished state from an earlier crash, immediately execute catch-up square-off. Tested in Task 7.
6. **Orphan SL Order Prevention & Exchange Trigger Rule:** Software stop-loss exits immediately cancel resting exchange SL orders to prevent position flipping; verify LTP < TriggerPrice before modifying SL to breakeven. Tested in Task 6.
7. **Execution Fill Price Integrity:** Entry fill prices are verified > 0 from order execution reports to prevent false ₹0-based stop-out triggers. Tested in Task 5.

---

### Task 1: Module Scaffolding & Multi-Module Integration

**Files:**
- Modify: `settings.gradle.kts`
- Create: `strategy/build.gradle.kts`
- Modify: `broker/build.gradle.kts`
- Test: `strategy/src/test/java/com/trading/bot/strategy/SmokeTest.java`

**Interfaces:**
- Produces: `:strategy` subproject depending on `:broker` (api models and ports) and `:notifications` (alert port). `:broker` subproject depends on `:strategy` so Spring Boot runtime scans components.

- [ ] **Step 1: Write temporary test** in `strategy/src/test/java/com/trading/bot/strategy/SmokeTest.java` asserting `true`.
- [ ] **Step 2: Update `settings.gradle.kts` and build files** to include `:strategy`.
- [ ] **Step 3: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 4: Run full build** `./gradlew build` → expected: BUILD SUCCESSFUL with quality gates.
- [ ] **Step 5: Commit** `build(strategy): scaffold strategy subproject in gradle build`.

---

### Task 2: Strategy Configuration & Properties

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/config/StrategyProperties.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/config/ExecutionMode.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/config/StrategyPropertiesTest.java` (replaces SmokeTest)

**Interfaces:**
- Produces:
  - `enum ExecutionMode { PAPER, LIVE }`
  - `@ConfigurationProperties("strategy") @Validated StrategyProperties(ExecutionMode executionMode, int lots, double slPercentage, boolean trailToBreakeven, LocalTime hedgeTime, LocalTime entryTime, LocalTime exitTime, int niftyHedgeDistance, int sensexHedgeDistance)` with defaults `PAPER`, `2`, `30.0`, `true`, `09:45`, `10:00`, `15:00`, `1000`, `2000`.

- [ ] **Step 1: Write failing test** asserting defaults, custom property overrides, and validation (lots > 0, slPercentage > 0, trailToBreakeven boolean).
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `ExecutionMode.java` and `StrategyProperties.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): validated configuration properties and execution mode`.

---

### Task 3: Instrument Resolver, Expiry Mathematics & Symbol Builder

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/InstrumentResolver.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/ExpiryCalculator.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/model/TargetIndex.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/model/StrikeSelection.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/InstrumentResolverTest.java`

**Interfaces:**
- Produces:
  - `enum TargetIndex { NIFTY, SENSEX }` with step size (50 vs 100), hedge distance (1,000 vs 2,000), lot size (65 vs 20), spot token (`256265L` for NIFTY, `265L` for SENSEX), and schedule days (Mon/Tue vs Wed/Thu).
  - `ExpiryCalculator`: determines current near-week expiry date for NIFTY (Thursday) and SENSEX (Friday), adjusting for holidays.
  - `record StrikeSelection(int atmStrike, int otmCeStrike, int otmPeStrike)`
  - `@Component InstrumentResolver`:
    - `int computeAtmStrike(TargetIndex index, double spotPrice)`: rounds to nearest 50 for Nifty, nearest 100 for Sensex; midpoint tie-breaker rounds up (Review Focus 1).
    - `StrikeSelection selectStrikes(TargetIndex index, double spotPrice)`: returns ATM, +20 OTM CE strike, -20 OTM PE strike.
    - `String formatContractSymbol(TargetIndex index, LocalDate expiry, int strike, String optionType)`: formats valid Kite derivative tradingsymbol (e.g. `NIFTY26OCT25000CE` or `NIFTY26O2925000CE`).

- [ ] **Step 1: Write failing test** covering:
  - NIFTY strike rounding (24,924 -> 24,900; 24,926 -> 24,950; 24,975.00 -> 25,000 midpoint).
  - SENSEX strike rounding (80,149 -> 80,100; 80,150 -> 80,200).
  - OTM hedge distance math (NIFTY ATM 25,000 -> CE 26,000, PE 24,000; SENSEX ATM 80,000 -> CE 82,000, PE 78,000).
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `TargetIndex.java`, `StrikeSelection.java`, and `InstrumentResolver.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): index strike math and instrument resolution`.

---

### Task 4: Strategy State Machine & Atomic Daily Persistence

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/model/StrategyState.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/model/TradeLeg.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/model/DailyTradeRecord.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/StrategyStateRepository.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/StrategyStateRepositoryTest.java`

**Interfaces:**
- Produces:
  - `enum StrategyState { IDLE, HEDGED, STRADDLE_ACTIVE, ONE_LEG_STOPPED, COMPLETED, ABORTED }`
  - `record TradeLeg(String symbol, InstrumentToken token, int quantity, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal slPrice, OrderId slOrderId, boolean isOpen)`
  - `record DailyTradeRecord(LocalDate date, TargetIndex index, StrategyState state, TradeLeg hedgeCe, TradeLeg hedgePe, TradeLeg shortCe, TradeLeg shortPe, BigDecimal realizedPnl)`
  - `@Component StrategyStateRepository`: saves atomically via temp file + `ATOMIC_MOVE` to prevent file corruption, loads today's record from `data/strategy_state.json`.

- [ ] **Step 1: Write failing test** asserting serialization, deserialization, state updates, and recovery from disk on simulated restart (Review Focus 4).
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** model records and `StrategyStateRepository.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): daily trade state persistence and recovery`.

---

### Task 5: Strategy Execution Gateway (Live vs Paper)

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/gateway/StrategyExecutionGateway.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/gateway/LiveStrategyGateway.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/gateway/PaperStrategyGateway.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/gateway/ExecutionGatewayTest.java`

**Interfaces:**
- Produces:
  - `interface StrategyExecutionGateway`:
    - `BigDecimal executeBuyMarket(String symbol, InstrumentToken token, Exchange exchange, int quantity)`
    - `BigDecimal executeSellMarket(String symbol, InstrumentToken token, Exchange exchange, int quantity)`
  - `LiveStrategyGateway implements StrategyExecutionGateway`: calls `Broker.placeOrder()`.
  - `PaperStrategyGateway implements StrategyExecutionGateway`: samples live market price from `Broker.quotes()`, logs paper fill, never calls `Broker.placeOrder()`.

- [ ] **Step 1: Write failing test** proving `LiveStrategyGateway` routes to `Broker.placeOrder()` and `PaperStrategyGateway` uses `Broker.quotes()` with 0 broker order calls.
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `StrategyExecutionGateway.java`, `LiveStrategyGateway.java`, and `PaperStrategyGateway.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): live and paper execution gateways`.

---

### Task 6: Position Risk Manager & 30% Stop-Loss with Breakeven Trailing

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

**Interfaces:**
- Consumes: `StrategyExecutionGateway`, `StrategyStateRepository`, `Broker.quotes()`, `NotificationPublisher`, `StrategyProperties`.
- Produces:
  - `@Component PositionRiskManager`:
    - `void evaluateRisk(DailyTradeRecord record)`: polls quotes for active short legs; if current premium >= entry * 1.30, triggers market buy to close leg; if `props.trailToBreakeven()`, immediately updates surviving leg's SL to its entry cost (`slPrice = entryPrice`).
    - Handles breakeven exit: if market subsequently whipsaws and surviving leg reaches entry price, closes surviving leg at cost.
    - Handles double leg stop collision gracefully (Review Focus 3).

- [ ] **Step 1: Write failing test** covering:
  - Entry 100.0, price hits 130.5 -> CE exits, PE stop-loss updated to entry price (100.0).
  - Subsequent reversal where PE touches 100.0 -> PE exits at breakeven.
  - Entry 100.0, price 125.0 -> no exit.
  - Both legs spiking above 30% SL -> both exit cleanly without collision.
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `PositionRiskManager.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): position risk manager with independent 30% stop-loss`.

---

### Task 7: Intraday Strategy Engine & Scheduling Checkpoints

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java`

**Interfaces:**
- Consumes: all Task 2–6 components, `Clock`.
- Produces:
  - `@Component IntradayOptionStrategy`:
    - `@Scheduled(cron = "0 45 9 * * MON-THU", zone = "Asia/Kolkata") void executeHedge()`: samples spot, buys ±20 OTM wings.
    - `@Scheduled(cron = "0 0 10 * * MON-THU", zone = "Asia/Kolkata") void executeStraddle()`: sells ATM straddle only if hedges succeed; aborts if hedge missing (Review Focus 2).
    - `@Scheduled(fixedDelay = 1000) void checkRisk()`: evaluates 30% SL during active trading.
    - `@Scheduled(cron = "0 0 15 * * MON-THU", zone = "Asia/Kolkata") void executeSquareOff()`: closes all open short legs and hedge wings.
    - Handles post-15:00 restart catch-up square-off (Review Focus 5).

- [ ] **Step 1: Write failing test** verifying 09:45 hedge, 10:00 straddle, hedge failure aborting straddle, and 15:00 full square-off.
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `IntradayOptionStrategy.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): intraday option strategy orchestrator and checkpoints`.

---

### Task 8: Strategy Telemetry & Telegram Alerts

**Files:**
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/StrategyAlertService.java`
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/StrategyAlertServiceTest.java`

**Interfaces:**
- Consumes: `NotificationPublisher`.
- Produces:
  - `@Component StrategyAlertService`:
    - `notifyHedgeEntry(DailyTradeRecord record)`: publishes clean trade notification (no `[INFO]`).
    - `notifyStraddleEntry(DailyTradeRecord record)`: publishes strikes, premiums, and 30% SL levels.
    - `notifyStopLossTriggered(TradeLeg leg, BigDecimal exitPrice)`: publishes SL trigger alert.
    - `notifyDailySquareOff(DailyTradeRecord record)`: publishes final P&L breakdown.

- [ ] **Step 1: Write failing test** asserting notification structure, payload fields, and absence of `[INFO]` prefix.
- [ ] **Step 2: Run** `./gradlew :strategy:test` → expected: FAIL.
- [ ] **Step 3: Implement** `StrategyAlertService.java`.
- [ ] **Step 4: Run** `./gradlew :strategy:test` → expected: PASS.
- [ ] **Step 5: Commit** `feat(strategy): strategy alert formatting and notification events`.

---

### Task 9: Runtime Integration, End-to-End Test & Build Verification

**Files:**
- Modify: `broker/src/main/java/com/trading/bot/broker/TradingBotApplication.java`
- Modify: `broker/build.gradle.kts`
- Modify: `application.yml`
- Test: `broker/src/test/java/com/trading/bot/broker/StrategyIntegrationTest.java`

**Interfaces:**
- Produces: Integrated Spring Boot application running both broker/notifications and strategy modules under one container.

- [ ] **Step 1: Write integration test** in `:broker` testing that `TradingBotApplication` boots with `StrategyProperties`, autowires `IntradayOptionStrategy`, and validates actuator endpoints.
- [ ] **Step 2: Run** `./gradlew :broker:test` → expected: FAIL before wiring.
- [ ] **Step 3: Wire** `broker/build.gradle.kts` and `@EnableConfigurationProperties(StrategyProperties.class)`.
- [ ] **Step 4: Run full verification** `./gradlew build` → expected: BUILD SUCCESSFUL across `:notifications`, `:broker`, `:strategy` with all quality gates.
- [ ] **Step 5: Commit** `feat(broker): integrate strategy module into boot application`.
