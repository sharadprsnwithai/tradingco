# WebSocket Fill Detection + Risk-Loop Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When one short leg's SL fills on the exchange, bring the surviving leg to breakeven immediately via Kite's WebSocket order-update push (sub-second), while the existing 1-second REST loop stays as the safety net — and fix two pre-existing risk-loop bugs found during review (fallback double-buy window, partial-fill blindness).

**Architecture:** `KiteTickerClient` gains text-frame handling: parse Kite `{"type":"order","data":{...}}` updates into a new `OrderUpdate` record and fan out to listeners via a new `Broker.subscribeOrderUpdates` port method (default no-op → polling-only degradation is free). The strategy registers one listener; a FILLED update hands off to a single-thread executor which calls the now-synchronized `checkRisk()` — the WS is a *wake-up hint*, never the fill truth: `evaluateRisk` still re-verifies against the order book and prices exits from exchange data. The client also gains self-healing it never had: `onClose`/`onError`/startup-failure schedule a single guarded reconnect with a fixed delay and a fresh access token. Two hardening fixes then land in `PositionRiskManager`: fallback cancel-failure must not abort the tick or double-buy, and partially-filled SL orders (the stop was hit but the limit didn't absorb the full quantity) must be detected and completed at market with a blended exit price.

**Tech Stack:** Java 25, Spring Boot 4.1, Jackson 3 (`tools.jackson`, already on both classpaths), JDK `java.net.http.WebSocket`, JUnit 5 + Mockito. No new dependencies.

**Spec:** No separate spec — this plan implements the review findings F1–F10 from the code analysis of 2026-10-23 (recorded in `.superpowers/sdd/2026-10-23-broker-notifications/progress.md` context). The governing design rules: (1) WS = hint, order book = truth; (2) one lock serializes all risk evaluation; (3) the WS reader thread never does REST or blocking work; (4) nothing on the WS path may throw past `onText`.

## Global Constraints

- Java 25 toolchain; package roots `com.trading.bot.broker` / `com.trading.bot.strategy`.
- No new Gradle dependencies. Jackson 3 (`tools.jackson.databind.ObjectMapper`) is already available in both modules.
- Commands from repo root in PowerShell use `.\gradlew.bat`. Format with `.\gradlew.bat spotlessApply` before committing; `.\gradlew.bat build` must stay green (Spotless/PMD/Checkstyle/SpotBugs).
- TDD per task: failing test first, confirm FAIL, implement, confirm PASS, full build green, commit.
- Kite order-update payload shape: root `{"type":"order","data":{order_id, status, average_price, filled_quantity, ...}}`; non-`order` types (e.g. `error`) must be ignored.
- `OrderStatus.fromKite` maps `"COMPLETE"`→FILLED, `"OPEN"`/`"TRIGGER PENDING"`→OPEN — reuse it; do not add mappings.
- The 1-second `@Scheduled(fixedDelay = 1000)` REST loop remains; WS only accelerates it.

## Review Focus

Failure modes no single task's happy-path test covers; each gets a pinning test in the task named.

1. **WS fires for a fill the REST loop already reconciled** (stale/duplicate update) → must not double-exit. Pinned in Task 2: firing `onSlFill` twice, and firing after the loop already closed the leg, produces no second exit.
2. **Non-SL FILLED orders** (hedge fills, square-off fills, manual Kite-app orders) reach the listener → must not trigger risk work beyond the cheap `checkRisk` early-return. Pinned in Task 2: any FILLED order is accepted by the handler (filtering is `checkRisk`'s state gate, by design) and a rejected-status update is dropped before the executor.
3. **Malformed, partial, or non-order text frames** (chunked JSON, `error` frames, garbage) → client must log-and-drop, never throw, and keep processing subsequent binary ticks. Pinned in Task 1: each case asserted, plus LTP cache still works after a bad frame.
4. **Fallback race**: SL actually filled on exchange but the order-book REST snapshot lags → `cancelOrder` fails → today the exception kills the whole tick (PE leg un-evaluated). Pinned in Task 3: cancel throwing → no market buy for that leg, the other leg still evaluated, no exception escapes.
5. **Partial SL fill** (stop hit, limit absorbed part; status stays OPEN with `filledQuantity>0`) → position silently half-exited today. Pinned in Task 4: partially-filled SL triggers remainder-cancel + market exit of the pending quantity at a blended exit price; also the CANCELLED-with-partial case (remainder already gone at the exchange — no cancel call, still exits the open remainder).
6. **WS dies mid-session** (server close, network blip, bad token at startup) → today `connect()` never retries and `onClose`/`onError` just flag `connected=false` — push stays dead until app restart while the app *looks* healthy. Pinned in Task 5: close and error each schedule exactly one reconnect attempt, no duplicate scheduling, initial connect failure also schedules, intentional shutdown never schedules, each attempt re-reads a fresh access token.
7. **`/quotes` 429 during WS downtime** — Kite's Quote endpoint is limited to 1 req/second (individual); the loop's `fixedDelay=1000` sits exactly on that limit whenever the WS LTP cache can't serve prices, and an unguarded 429 from `broker.quotes()` at `PositionRiskManager.java:102` kills the whole tick. Pinned in Task 3: `quotes()` throwing `RateLimitException` (or any `BrokerException`) leaves the order-book exchange-truth path fully functional and no exception escapes `evaluateRisk`.

## Rate-limit accounting (why the loop is safe)

- `orderBook()`: 1–2 req/s against the 10 req/s combined GET bucket — safe even with a WS-triggered run colliding with a scheduled tick.
- `quotes()`: hard 1 req/s per-endpoint limit. With WS connected, `KiteBroker.quotes()` is WS-first (`KiteBroker.java:269-299`) — served from the ticker LTP cache, zero REST calls once warm. REST `/quotes` fires only during WS downtime/warm-up at ≤1 req/s (`fixedDelay=1000` spacing, start-to-start > 1s), and Task 3's containment degrades a 429 to "quote-fallback paused this tick" instead of a dead tick. WS-triggered runs during warm-up are the only 2-in-one-second case — covered by the same containment.
- Order placement/modification (SL, trail-to-breakeven): a handful per day vs 10/s and 400/min order limits — not a factor.

---

### Task 1: Broker — order-update stream (port + WS parsing)

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/api/OrderUpdateListener.java`
- Create: `broker/src/main/java/com/trading/bot/broker/api/model/OrderUpdate.java`
- Modify: `broker/src/main/java/com/trading/broker/api/Broker.java` (add default method)
- Modify: `broker/src/main/java/com/trading/bot/broker/kite/KiteBroker.java` (override)
- Modify: `broker/src/main/java/com/trading/bot/broker/kite/KiteTickerClient.java` (`onText` + listener registry)
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteTickerClientTest.java` (extend existing)

**Interfaces:**
- Consumes: existing `KiteTickerClient(KiteProperties, SessionDelegate)` constructor, `OrderStatus.fromKite`, `OrderId`, `getLtp` LTP cache (unchanged).
- Produces (relied on by Task 2):
  - `public record OrderUpdate(OrderId orderId, OrderStatus status, BigDecimal averagePrice, int filledQuantity)` in `broker.api.model`
  - `public interface OrderUpdateListener { void onOrderUpdate(OrderUpdate update); }` in `broker.api`
  - `void Broker.subscribeOrderUpdates(OrderUpdateListener listener)` — **default no-op** in the interface; `KiteBroker` overrides to delegate to `tickerClient` when non-null, else `super` (polling-only degradation needs no special casing elsewhere)

- [ ] **Step 1: Write the failing tests in `KiteTickerClientTest`**

Build a Kite order-update JSON string helper in the test (`{"type":"order","data":{"order_id":"123","status":"COMPLETE","average_price":152.5,"filled_quantity":75}}`), mock `WebSocket`, and assert via a captured `OrderUpdateListener`:

- `orderUpdateJsonNotifiesListenerWithMappedOrderUpdate` — valid COMPLETE frame → one `OrderUpdate` with `OrderId("123")`, `OrderStatus.FILLED`, `averagePrice 152.5`, `filledQuantity 75`
- `triggerPendingStatusMapsToOpenAndIsStillDelivered` — `"status":"TRIGGER PENDING"` → `OrderStatus.OPEN` delivered (filtering is the consumer's job)
- `malformedJsonIsDroppedWithoutThrowing` — `"not json"` → no listener call, `onText` returns normally
- `nonOrderTypeFramesAreIgnored` — `{"type":"error","data":{...}}` → no listener call
- `chunkedFramesAccumulateBeforeParsing` — `onText` called twice with the same JSON split mid-string (`isLast=false` then `true`) → exactly one `OrderUpdate`
- `ltpCacheStillWorksAfterBadFrame` — after a malformed text frame, existing binary-tick `getLtp` behavior unchanged
- `listenerExceptionDoesNotPropagate` — listener throws RuntimeException → `onText` returns normally

- [ ] **Step 2: Run to verify FAIL**

Run: `.\gradlew.bat :broker:test --tests KiteTickerClientTest`
Expected: FAIL/compile errors — `onText` override and `OrderUpdate` do not exist.

- [ ] **Step 3: Implement**

- `OrderUpdate` record as in Interfaces (no validation beyond what tests pin; `averagePrice` may be null when Kite omits it).
- `OrderUpdateListener` single-method interface, javadoc: invoked on the WebSocket reader thread — implementations must not block or do I/O; hand off.
- `Broker.subscribeOrderUpdates` as a **default no-op** with javadoc stating: no-op when live updates unavailable; polling remains the correctness path.
- `KiteTickerClient`: add `CopyOnWriteArrayList<OrderUpdateListener> orderListeners` and a `tools.jackson.databind.ObjectMapper` field; `public void subscribeOrderUpdates(OrderUpdateListener)`; override `onText(WebSocket, CharSequence, boolean, boolean)` accumulating into a per-message `StringBuilder` (reset on `isLast`), and when the message completes: wrap **parse + fan-out in try/catch(Exception)** → `LOG.warn` + drop; parse root `type=="order"` else debug-log-and-ignore; build `OrderUpdate` from `data` (`order_id` text, `status` via `OrderStatus.fromKite`, `average_price` decimal-or-null, `filled_quantity` int); notify each listener in its own try/catch.
- `KiteBroker`: override `subscribeOrderUpdates` → `tickerClient != null ? tickerClient.subscribeOrderUpdates(listener) : super` (no-op path).

- [ ] **Step 4: Run tests to verify PASS**

Run: `.\gradlew.bat :broker:test --tests KiteTickerClientTest`
Expected: all pass, including pre-existing tick tests.

- [ ] **Step 5: Full build + commit**

Run: `.\gradlew.bat build` → green. Commit `feat(broker): stream Kite order updates over ticker WebSocket`.

---

### Task 2: Strategy — WS-triggered risk check, serialized with the loop

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/IntradayOptionStrategy.java` (`checkRisk` @ ~302, constructor @ 56-98)
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/IntradayOptionStrategyTest.java`

**Interfaces:**
- Consumes: `Broker.subscribeOrderUpdates(OrderUpdateListener)`, `OrderUpdate`, `OrderStatus` (Task 1).
- Produces: package-private `void onSlFill(OrderUpdate update)` and a 10-arg constructor taking `Executor` (test seam); `checkRisk()` becomes `synchronized` — any future caller gets serialization for free.

- [ ] **Step 1: Write the failing tests in `IntradayOptionStrategyTest`**

Construct the strategy with the existing mocks plus a **direct executor** (`Runnable::run`) in the new constructor slot. Active STRADDLE_CE_ONLY/STRADDLE_ACTIVE state with an open short leg whose `slOrderId` is set; `broker.orderBook()` returns the SL order as FILLED (mirror the `PositionRiskManagerTest` fill fixtures).

- `subscribeOrderUpdatesRegistersListenerAtStartup` — after construction, an `ArgumentCaptor<OrderUpdateListener>` captures the listener passed to `broker.subscribeOrderUpdates`
- `filledOrderUpdateTriggersImmediateRiskEvaluation` — listener receives FILLED update for the SL order id → `gateway`/`riskManager` interactions show the risk path ran **synchronously in the test** (direct executor), not on a timer
- `nonFilledUpdateIsIgnored` — listener receives an OPEN update → risk path never invoked
- `duplicateFillUpdateDoesNotDoubleExit` — fire the same FILLED update twice → exactly one set of exit interactions (second `checkRisk` sees the closed leg and early-returns)
- `wsTriggerWithNoActiveStateDoesNothing` — no record for today → zero gateway interactions

- [ ] **Step 2: Run to verify FAIL**

Run: `.\gradlew.bat :strategy:test --tests IntradayOptionStrategyTest`
Expected: FAIL — no `subscribeOrderUpdates` call, no `onSlFill`.

- [ ] **Step 3: Implement**

- `checkRisk()` gains `synchronized` (single lock for scheduler thread + WS-triggered runs; each invocation loads the record fresh inside the lock — the existing body already does this, so serialization is complete).
- New fields: `ExecutorService slTriggerExecutor` — single thread, **daemon** thread factory, name `sl-trigger`; `@PreDestroy` `shutdown()` it.
- New 10-arg constructor `(…existing 9…, Executor slTriggerExecutor)`; the existing `@Autowired` 9-arg constructor delegates with `Executors.newSingleThreadExecutor(...)` daemon factory. Update the ~5 existing test call sites to the 10-arg form with `Runnable::run`.
- `@PostConstruct void registerOrderUpdateListener()` → `broker.subscribeOrderUpdates(this::onSlFill)`.
- `void onSlFill(OrderUpdate update)` (package-private): `if (update.status() != OrderStatus.FILLED) return;` else `LOG.debug` (order id + fill price) and `slTriggerExecutor.execute(this::checkRisk)`. No filtering by order id here — `checkRisk`'s state gate is the single authority; the handler stays a one-line wake-up (F6: debug log distinguishes "WS alive but no match" from "WS dead").

- [ ] **Step 4: Run tests to verify PASS**

Run: `.\gradlew.bat :strategy:test --tests IntradayOptionStrategyTest`
Expected: all pass, including pre-existing tests (updated constructor call sites only).

- [ ] **Step 5: Full build + commit**

Run: `.\gradlew.bat build` → green. Commit `feat(strategy): trigger risk check on WebSocket SL fill`.

---

### Task 3: Harden fallback — failing cancel or quote fetch must not kill the tick

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java` (CE fallback ~124-134, PE fallback ~224-234)
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

**Interfaces:**
- Consumes: existing `gateway.cancelOrder(OrderId)` (throws `BrokerException` on already-closed orders in live mode), `BrokerException`.
- Produces: unchanged public surface; `evaluateRisk` no longer propagates `BrokerException` from the fallback path.

- [ ] **Step 1: Write the failing tests in `PositionRiskManagerTest`**

- `fallbackCancelFailureSkipsMarketExitAndPreservesLeg` — CE: SL order absent from the (lagging) order book, LTP ≥ slPrice, `gateway.cancelOrder` throws `BrokerException` → no `executeBuyMarket` call for CE, leg stays open in the returned record, no exception escapes `evaluateRisk`.
- `fallbackCancelFailureOnOneLegStillEvaluatesTheOther` — same CE scenario, but PE's SL order shows FILLED in the book → PE exit interactions still happen in the same call (today the CE exception aborts the tick before PE).
- `quoteFetchRateLimitDoesNotAbortSlDetection` — `broker.quotes(...)` throws `RateLimitException` while the order book shows the CE SL as FILLED → CE exit interactions still happen (exchange-truth path needs no quotes), no exception escapes `evaluateRisk`.

- [ ] **Step 2: Run to verify FAIL**

Run: `.\gradlew.bat :strategy:test --tests PositionRiskManagerTest`
Expected: FAIL — the `BrokerException` currently propagates.

- [ ] **Step 3: Implement**

In each leg's quote-fallback branch, wrap the `gateway.cancelOrder(slOrderId)` call in try/catch(`BrokerException`): log warn ("SL order no longer cancellable — likely just filled; deferring to order-book truth next tick"), and skip that leg's market exit (leave the leg open — the next tick's order-book check catches the fill). Everything else in the tick proceeds. Applies identically to the CE and PE blocks (and the trail-path cancel at ~163 gets the same guard: a failed cancel there skips the emergency market exit for that tick, deferring to reconciliation).

Also guard the `broker.quotes(refs)` call at `PositionRiskManager.java:102`: wrap in try/catch(`BrokerException`) → on failure log warn and use an empty quotes map. Consequence: the quote-fallback trigger (`lastPrice >= slPrice`) is inert for that tick, but the exchange-truth path (order book FILLED detection) is untouched — SL detection never dies to a 429, and each leg's `q == null` check already treats missing quotes as "not quote-triggered".

- [ ] **Step 4: Run tests to verify PASS**

Run: `.\gradlew.bat :strategy:test --tests PositionRiskManagerTest` then full `:strategy:test`
Expected: all pass.

- [ ] **Step 5: Full build + commit**

Run: `.\gradlew.bat build` → green. Commit `fix(strategy): contain fallback cancel and quote-fetch failures so SL detection never dies`.

---

### Task 4: Detect and complete partially-filled SL orders

**Files:**
- Modify: `strategy/src/main/java/com/trading/bot/strategy/engine/PositionRiskManager.java` (fill-detection blocks ~109-118 and ~209-218)
- Test: `strategy/src/test/java/com/trading/bot/strategy/engine/PositionRiskManagerTest.java`

**Interfaces:**
- Consumes: existing `Order.filledQuantity()`, `Order.pendingQuantity()`, `Order.averagePrice()`, `Order.status()` (all present in the `Order` record).
- Produces: `evaluateRisk` treats a partially-executed SL as an SL hit for the whole leg (stop was hit → complete the exit); leg's `exitPrice` is the quantity-weighted blend of exchange fills and the market exit of the remainder.

- [ ] **Step 1: Write the failing tests in `PositionRiskManagerTest`**

Fixture: short CE leg qty 100, SL order in book with `status OPEN`, `filledQuantity 40`, `pendingQuantity 60`, `averagePrice 150`; `gateway.executeBuyMarket` returns 160.

- `partiallyFilledSlCompletesExitAtMarketWithBlendedPrice` — `cancelOrder` called with the SL id (remainder pulled), `executeBuyMarket` called with quantity **60** (the pending remainder), leg closed with `exitPrice == (40*150 + 60*160)/100 == 156`, trail-to-breakeven logic then proceeds as for a normal SL hit.
- `cancelledSlWithPartialFillStillExitsOpenRemainder` — same, but SL `status CANCELLED`, `filledQuantity 40` → **no** `cancelOrder` call (exchange already canceled), `executeBuyMarket` qty 60, blended `exitPrice`.
- `fullyFilledSlUsesExchangePriceUnchanged` — regression pin: `status FILLED`, `filledQuantity 100` → existing behavior, exit at exchange `averagePrice`, no market buy.

- [ ] **Step 2: Run to verify FAIL**

Run: `.\gradlew.bat :strategy:test --tests PositionRiskManagerTest`
Expected: FAIL — partial case currently ignored (status != FILLED).

- [ ] **Step 3: Implement**

In each leg's fill-detection block, after the existing full-FILLED check, add the partial rule: `slOrder.status() != OrderStatus.CANCELLED || slOrder.filledQuantity() > 0`, combined as — when `filledQuantity > 0 && filledQuantity < quantity` (partial execution happened while the leg is still open): set filled-at-exchange with `exitPrice = (filled*averagePrice + pending*marketExit)/quantity` where `marketExit = gateway.executeBuyMarket(symbol, token, exchange, pendingQuantity)`; if status is not CANCELLED, `cancelOrder` the remainder first (same BrokerException guard pattern as Task 3: a failed cancel here means the remainder already filled → the next tick's full-FILLED path takes over, so skip the market buy and leave reconciliation to it). Mirror the logic in both the CE and PE blocks. Reuse the existing closed-leg reconstruction + trail-to-breakeven flow unchanged — the leg simply closes with the blended price.

- [ ] **Step 4: Run tests to verify PASS**

Run: `.\gradlew.bat :strategy:test --tests PositionRiskManagerTest` then full `:strategy:test`
Expected: all pass.

- [ ] **Step 5: Full build + commit**

Run: `.\gradlew.bat build` → green. Commit `fix(strategy): complete partially-filled stop-loss orders at market`.

---

### Task 5: WebSocket reconnect — self-healing connection

**Files:**
- Modify: `broker/src/main/java/com/trading/bot/broker/kite/KiteTickerClient.java` (`connect` :59, `onClose` :142, `onError` :151, `@PreDestroy`)
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteTickerClientTest.java` (extend existing)

**Interfaces:**
- Consumes: existing `session.accessToken()` (fresh token per attempt — `connect()` already re-reads it at :61 and re-subscribes `subscribedTokens` at :77-79, so a reconnect attempt needs nothing new).
- Produces: no port/API change — reconnect is entirely internal to `KiteTickerClient`.

- [ ] **Step 1: Write the failing tests in `KiteTickerClientTest`**

Use a mock `SessionDelegate`; inject a mock/inlined `ScheduledExecutorService` via a new package-private constructor `KiteTickerClient(KiteProperties, SessionDelegate, ScheduledExecutorService)` (the existing public 2-arg constructor creates a daemon single-thread scheduler and delegates — existing tests unchanged).

- `serverCloseSchedulesReconnect` — invoke `onClose(ws, 1000, "bye")` → exactly one `schedule` call with 30-second delay
- `errorSchedulesReconnect` — invoke `onError(ws, new IOException("boom"))` → one `schedule` call
- `closeAfterErrorSchedulesOnlyOnce` — `onError` then `onClose` before the delay elapses → exactly one `schedule` call (single-pending guard)
- `initialConnectFailureSchedulesRetry` — `session.accessToken()` returns null → `connect()` schedules a retry instead of silently giving up
- `reconnectAttemptUsesFreshAccessToken` — run the scheduled task (capture the `Runnable`, run it inline) → `session.accessToken()` called again, new connection attempted with the new token
- `shutdownPreventsFurtherReconnects` — `@PreDestroy` invoked, then `onClose` → zero `schedule` calls after shutdown

- [ ] **Step 2: Run to verify FAIL**

Run: `.\gradlew.bat :broker:test --tests KiteTickerClientTest`
Expected: FAIL — no scheduling exists today (`onClose` only logs).

- [ ] **Step 3: Implement**

- New fields: `ScheduledExecutorService reconnectScheduler` (daemon thread factory, name `kite-ticker-reconnect`), `AtomicBoolean reconnectPending`, `volatile boolean stopped`.
- Package-private 3-arg constructor as in Interfaces; existing constructor delegates with a real daemon scheduler.
- `void scheduleReconnect()` (package-private for tests): return immediately if `stopped` or `reconnectPending.compareAndSet(false, true)` fails; otherwise `reconnectScheduler.schedule(() -> { reconnectPending.set(false); if (!stopped) connect(); }, 30, TimeUnit.SECONDS)`.
- Call `scheduleReconnect()` from: `onClose` (after `connected=false`), `onError` (after `connected=false`), and `connect()`'s catch block (replacing the silent give-up; keep the warn log). The null/blank-token early-return at :62 also calls it (SessionRefresher may still be mid-relogin — a retry in 30s picks the new token).
- `@PreDestroy`: set `stopped = true`, `reconnectScheduler.shutdownNow()`. Existing `@PreDestroy` (if any) merged.
- Comment the deliberate limit: `shortcut: fixed 30s retry, no exponential backoff — the 1s REST loop covers any WS outage; upgrade to backoff only if Kite rate-limits reconnect attempts`.

- [ ] **Step 4: Run tests to verify PASS**

Run: `.\gradlew.bat :broker:test --tests KiteTickerClientTest`
Expected: all pass, including Task 1's order-update tests (verify reconnect scheduling does not interfere with text-frame handling).

- [ ] **Step 5: Full build + commit**

Run: `.\gradlew.bat build` → green. Commit `feat(broker): reconnect KiteTicker on close, error, or failed startup connect`.
