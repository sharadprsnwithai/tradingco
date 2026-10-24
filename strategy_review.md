# Strategy Module Review

## What this change does
The review evaluated the intraday option-selling strategy: entry/exit checkpoints, position risk & stop-loss manager, execution gateways (PAPER/LIVE), models, and file-based state persistence. All verified flaws have been resolved with unit tests and clean quality gates.

---

## Flaw Analysis & Resolution Status

### 1. Stop-loss monitoring loop never runs (`IntradayOptionStrategy.checkRisk`) — **FIXED**
- **Original finding:** The method had a comment describing a 1-second continuous risk loop, but lacked `@Scheduled` and production callers. In a live session, trailing SL, breakeven trailing, and `ONE_LEG_STOPPED` state transitions would never execute before 15:00 forced square-off.
- **Resolution:**
  - Added `@Scheduled(fixedDelay = 1000)` and `synchronized` modifier to `IntradayOptionStrategy.checkRisk()`.
  - Added unit tests in `IntradayOptionStrategyTest` asserting the presence and value of `@Scheduled(fixedDelay = 1000)` via reflection, plus verifying multi-invocation risk checking.
- **Commit:** `20f4223 fix(strategy): activate continuous stop-loss monitoring loop with @Scheduled`

---

### 2. Configured safety gates are dead code (`StrategyProperties`) — **FIXED**
- **Original finding:** `maxVixThreshold` (24), `maxSpreadPercentage` (1.5), and `profitTargetPercentage` (85) existed with defaults but were never read anywhere in the codebase.
- **Resolution:**
  - **`maxSpreadPercentage`**: Bound in `LiveStrategyGateway` during marketable limit order price derivation. Slippage buffer is clamped to `props.maxSpreadPercentage()` and tick-rounded via `TickUtils.roundToTick`.
  - **`maxVixThreshold`**: Implemented `isVixWithinThreshold()` circuit breaker in `IntradayOptionStrategy` using token `264969L` (`INDIA VIX`). Both `executeHedge()` and `executeStraddle()` abort entry if India VIX exceeds the threshold.
  - **`profitTargetPercentage`**: Wired into `PositionRiskManager.evaluatePositionRisk()` to square off legs when premium decays below target (`<= entryPrice * (1.0 - profitTargetPercentage / 100)`). Added `notifyProfitTargetHit` to `StrategyAlertService`.
- **Commits:**
  - `51430dc fix(strategy): make live gateway order tags collision-resistant and bind maxSpreadPercentage`
  - `a453ce5 feat(strategy): enforce India VIX circuit breaker before trade entry`
  - `da3fe6a feat(strategy): add profit target decay exit to position risk evaluation`

---

### 3. Duplicated `roundToTick` — **FIXED**
- **Original finding:** Identical method duplicated in `IntradayOptionStrategy` and `PositionRiskManager`.
- **Resolution:**
  - Centralized logic into a standalone package-private utility `TickUtils.roundToTick(double price, double tickSize)`.
  - Removed duplicate copies and re-routed both caller classes to `TickUtils`.
  - Added unit test suite `TickUtilsTest`.
- **Commit:** `8c33b38 refactor(strategy): centralize roundToTick in TickUtils`

---

### 4. Order tags can collide (`LiveStrategyGateway`) — **FIXED**
- **Original finding:** Tags used `System.currentTimeMillis() % 1_000_000`, causing collision risk for orders submitted within the same millisecond.
- **Resolution:**
  - Implemented monotonic generator `generateOrderTag(String prefix)` combining `System.currentTimeMillis() % 1_000_000_000L` with an `AtomicLong` sequence modulo `10_000`, formatted as `%s%09d%04d`.
  - Strictly adheres to Zerodha Kite's 20-character alphanumeric limit without collision.
  - Added concurrency & uniqueness tests in `ExecutionGatewayTest`.
- **Commit:** `51430dc fix(strategy): make live gateway order tags collision-resistant and bind maxSpreadPercentage`

---

### 5. NIFTY lot size (`TargetIndex`) — **REJECTED (FALSE ALARM)**
- **Original finding:** Speculated that `NIFTY(..., lots=65)` is incorrect and should be ~25.
- **Verification via Live Exchange Master:**
  - Queried live Zerodha Kite Connect instruments API via Kite MCP tool `search_instruments`.
  - Live query confirmed:
    - **NIFTY**: `exchange: "NFO"`, `lot_size: 65` (revised by NSE from 25/75 to 65).
    - **SENSEX**: `exchange: "BFO"`, `lot_size: 20`.
  - Changing lot size to 25 would cause immediate contract rejection by NSE upon order placement. Existing lot size `65` is verified correct.

---

## Verification & Quality Gates Summary
All quality gates and test suites pass cleanly across all subprojects:
- `./gradlew test` (all unit & integration tests pass)
- `./gradlew spotlessCheck` (Google Java Format compliance)
- `./gradlew checkstyleMain checkstyleTest` (Checkstyle clean)
- `./gradlew pmdMain pmdTest` (PMD rules clean)
- `./gradlew spotbugsMain` (SpotBugs clean)
- Overall `./gradlew check`: **BUILD SUCCESSFUL**