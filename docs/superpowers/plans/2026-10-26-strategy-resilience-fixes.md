# Strategy Resilience & Quality Fixes Implementation Plan

**Goal:** Fix remaining identified technical bugs and edge cases: state file corruption handling, market order slippage on exit, and holiday awareness in strategy scheduling.

**Architecture:**
- Improve `StrategyStateRepository` to throw on corrupt state files during crash recovery.
- Replace `MARKET` orders with marketable `LIMIT` orders for emergency exit.
- Integrate holiday awareness into `IntradayOptionStrategy` (using a simple static holiday map for current calendar year or similar if necessary).

## Tasks

### Task 4: Enhance State Repository Resilience

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/StrategyStateRepository.java`

**Steps:**
- [ ] Throw an `IllegalStateException` or `RuntimeException` on IO/parse failure in `load` if file exists, to prevent silent fallback to `IDLE` mid-day.

### Task 5: Prevent Market Order Slippage on Emergency Exits

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java`

**Steps:**
- [ ] Update emergency breakeven exits to use a marketable limit order with a small buffer instead of pure `MARKET` order.

### Task 6: Add Basic Holiday Awareness

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java`
- Create: `strategy/src/main/java/com/trading/bot/strategy/engine/ExchangeHolidayCalculator.java` (or add to `MarketHours`)

**Steps:**
- [ ] Add basic holiday awareness in `IntradayOptionStrategy` to return `null` target index on known exchange holidays.
