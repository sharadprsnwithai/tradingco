# Broker + Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Kite broker module (unattended TOTP login, order ops, signal-to-trade verification) and the notifications module (one publish() entry point → Reactor flux → Telegram), runnable in Docker.

**Architecture:** Two Gradle modules under one Spring Boot 4.1 app: `notifications` (library: port `NotificationPublisher` + Reactor two-lane flux + Telegram sender) and `broker` (app: port `Broker` + Kite adapter + poller/tracker/reconciler + health). Ports and adapters — strategy later injects interfaces only.

**Tech Stack:** Java 25, Gradle 9 (Kotlin DSL), Spring Boot 4.1.x, Lombok 1.18.48, reactor-core (Boot-managed), dev.samstevens.totp, Jackson (via Boot), JUnit 5 + spring-boot-starter-test + reactor-test. Quality gates: Spotless, PMD, Checkstyle, SpotBugs, SonarQube (dormant).

**Spec:** `docs/superpowers/specs/2026-10-23-kite-broker-module-design.md` (rev 2) — the plan argues from the spec; executors read both.

## Global Constraints

- Java 25 toolchain; Gradle wrapper pinned ≥9.1.0 (no Gradle 10 exists).
- Spring Boot 4.1.x, Lombok 1.18.48, TOTP lib `dev.samstevens:totp`. No other dependency without a stated need.
- Repo starts empty and is NOT a git repo — Task 1 runs `git init -b main`; every task commits.
- Commands run from repo root; in PowerShell use `.\gradlew.bat` where `./gradlew` is written.
- Secrets (env only, names exact): `KITE_API_KEY`, `KITE_USER_ID`, `KITE_PASSWORD`, `KITE_TOTP_SECRET`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`. Never in logs, exception messages, or cause chains.
- Exact tunables (defaults, tested): timeouts connect 5s / read 10s / place-read 15s; auth backoff 1m→…→20m cap, `max-attempts-per-hour=3`; LOST = ≥2 consecutive misses AND ≥30s since last positive observation; batch flush 1s OR 50; buffer 1000 drop-oldest; Telegram chunk 4096 chars, pace ≤28/min; retry sends 3×.
- All time math: `ZoneId.of("Asia/Kolkata")`, never container default TZ.
- Exception contract: only the sealed `BrokerException` hierarchy escapes the adapter; catch at two boundaries only (KiteHttpClient, scheduled runners); causes always chained.
- Alert-only: never auto-resubmit an order (§3, §7). Poller never declares LOST — Reconciler only.
- Quality gates wired into `check`; `./gradlew build` must stay green after every task.
- Package root: `com.trading.bot`. No Lombok in `broker/api/**` (records only).

## Review Focus

Spec-implied inputs no single task naturally proves, most likely first:

1. **Password/TOTP with URL-special chars** (`p&ss=w0rd`) in login form → wire bytes must be percent-encoded, not concatenated. Test in Task 8 (login form encoding test asserts encoded wire value).
2. **Non-JSON error body** (proxy HTML 502 page) → must become typed `BrokerException`, never a Jackson exception escaping. Test in Task 9 (malformed-body mapping test).
3. **Burst of order placements in the same second** → every auto-tag unique, ≤20 alnum chars. Test in Task 11 (100-order uniqueness test).
4. **Weekend / after-hours runs** → poller/reconciler gates false, zero Kite calls, no false LOST. Test in Task 13 (`MarketHours` boundary tests incl. Sunday 12:00 IST).
5. **Publish while Telegram transport stuck** → `publish()` returns in bounded time, never blocks the trading thread. Test in Task 3 (blocking-transport non-blocking test).

---

### Task 1: Repo, Gradle multi-module build, quality gates

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts` (root), `gradle/wrapper/*` (pin 9.x ≥9.1), `gradlew`, `gradlew.bat`, `.gitignore`
- Create: `notifications/build.gradle.kts`, `broker/build.gradle.kts`
- Create: `config/quality/pmd-ruleset.xml`, `config/quality/checkstyle.xml`, `config/quality/spotbugs-exclude.xml`

**Interfaces:**
- Produces: two subprojects `notifications`, `broker` (broker applies `org.springframework.boot` + `io.spring.dependency-management` application plugin; notifications is a plain java-library with `java-library` plugin). Root applies spotless (`google-java-format`, `spotlessApply`/`spotlessCheck` in `check`), PMD, Checkstyle, SpotBugs (subprojects), `org.sonarqube` (root, config from env, task no-ops without it). Lombok `compileOnly`+`annotationProcessor` both modules at 1.18.48.

- [ ] **Step 1: `git init -b main`** in repo root, write `.gitignore` (`build/`, `.gradle/`, `*.iml`, `.env`), commit "chore: init".
- [ ] **Step 2: Write build files + quality configs** exactly per Interfaces; wrapper pinned `9.8.0` (or current 9.x).
- [ ] **Step 3: Add one trivial passing test per module** (`notifications/src/test/java/.../SmokeTest.java`, `broker/src/test/java/.../SmokeTest.java` asserting `true`) — placeholder to prove test wiring; removed when real tests land in Task 2/5.
- [ ] **Step 4: Verify** Run: `./gradlew build` → expected: BUILD SUCCESSFUL, all of `spotlessCheck pmdMain checkstyleMain spotbugsMain` executed, `sonar` absent/no-op.
- [ ] **Step 5: Commit** `build: gradle multi-module + quality gates`.

### Task 2: Notification API (the port)

**Files:**
- Create: `notifications/src/main/java/com/trading/bot/notifications/api/NotificationPublisher.java`, `Notification.java`, `Severity.java`, `NotificationType.java`
- Test: `notifications/src/test/java/com/trading/bot/notifications/api/NotificationApiTest.java` (replaces SmokeTest)

**Interfaces:**
- Produces (Task 3, 4, 11, 12, 13, 14 consume):
  - `public interface NotificationPublisher { void publish(Notification n); }`
  - `public enum Severity { INFO, WARN, CRITICAL }`
  - `public enum NotificationType { STARTUP, SHUTDOWN, ORDER_SUBMITTED, ORDER_PARTIAL, ORDER_FILLED, ORDER_REJECTED, ORDER_CANCELLED, ORDER_LOST, ORDER_AMBIGUOUS, ORDER_RECOVERED, AUTH_DEGRADED, AUTH_RECOVERED, SYSTEM_ALERT }`
  - `public record Notification(Severity severity, NotificationType type, String title, String body, Throwable cause, Instant ts)` plus `static Notification of(Severity, NotificationType, String title, String body)` → cause=null, ts=`Instant.now()`.

- [ ] **Step 1: Failing tests** — `of(...)` factory sets ts≈now + null cause; canonical record equality; every `NotificationType` constant exists (array length == 13) so taxonomy can't silently shrink.
- [ ] **Step 2: Run** `./gradlew :notifications:test` → FAIL (classes not found).
- [ ] **Step 3: Implement** the four files per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(notifications): publisher port + notification model`.

### Task 3: NotificationFlux — two-lane pipeline

**Files:**
- Create: `notifications/src/main/java/com/trading/bot/notifications/internal/NotificationFlux.java`, `PipelineProperties.java`, `NotificationTransport.java`
- Test: `notifications/src/test/java/com/trading/bot/notifications/internal/NotificationFluxTest.java`

**Interfaces:**
- Consumes: Task 2 types.
- Produces:
  - `@Component NotificationFlux implements NotificationPublisher` — `publish(Notification)` uses `Sinks.Many`; CRITICAL severity → immediate lane, others → batch lane (flush on batchWindow `1s` OR batchSize `50`, whichever first). Bounded buffer `bufferSize=1000`, overflow → drop-oldest + WARN log + `notifications.dropped` Micrometer counter. `publish` uses best-effort emit — must return in bounded time regardless of transport state.
  - `interface NotificationTransport { void deliver(Notification n); }`
  - `@ConfigurationProperties("notifications") record PipelineProperties(Duration batchWindow, int batchSize, int bufferSize)` with defaults `1s/50/1000`.
- Notes for implementer: use reactor-test `StepVerifier.withVirtualTime` for the 1s window (add `testImplementation` `io.projectreactor:reactor-test`, Boot-managed — stated need: virtual-time testing of the batch window).

- [ ] **Step 1: Failing tests** — (a) 2 normal notifications + virtual time advance 1s → transport receives both in order; (b) 50 normal notifications without time advance → transport receives them (size flush); (c) CRITICAL published while normal batch is mid-window → delivered before the batch; (d) transport blocks 5s in another thread, `publish()` completes <100ms for 1100 notifications, dropped counter == 100+ (drop-oldest at overflow) — assert `notifications.dropped` > 0 via `SimpleMeterRegistry`; (e) exact order preserved within a batch.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `./gradlew :notifications:test` → PASS.
- [ ] **Step 5: Commit** `feat(notifications): two-lane flux pipeline, non-blocking, drop-oldest`.

### Task 4: TelegramSender + TelegramProperties

**Files:**
- Create: `notifications/src/main/java/com/trading/bot/notifications/internal/TelegramSender.java`, `TelegramProperties.java`
- Test: `notifications/src/test/java/com/trading/bot/notifications/internal/TelegramSenderTest.java`

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `@ConfigurationProperties("telegram") @Validated TelegramProperties(String botToken, String chatId)` — mandatory, blank → startup failure naming `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID`.
  - `@Component TelegramSender implements NotificationTransport` — ctor `(TelegramProperties, RestClient.Builder, MeterRegistry, Clock)`. `deliver(n)`: POST `https://api.telegram.org/bot{token}/sendMessage` JSON `{"chat_id","text"}` where text = severity/type/title/body (cause appended as message if present); text >4096 → truncate with `…[truncated]`; pacing token bucket 28/min using injected `Clock`; 3 retries with exponential backoff inside pacing budget; final failure → ERROR log (no token!) + `notifications.send.failures` counter, **never throws upward**. Bot token must never appear in logs or exception messages.

- [ ] **Step 1: Failing tests** — (a) `MockRestServiceServer`: payload has chat_id + formatted text; (b) body 5000 chars → message ≤4096 and contains truncation marker; (c) transport failure ×3 → counter incremented 3, no exception escapes, fourth log line is ERROR fallback; (d) two deliveries paced ≥2000ms apart using fake `Clock` (assert via recorded timestamps, no real sleep); (e) secrets: captured log output contains no bot token (canary).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; then `./gradlew build` green.
- [ ] **Step 5: Commit** `feat(notifications): telegram sender with chunking, pacing, fallback`.

### Task 5: Broker app skeleton + fail-fast config

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/TradingBotApplication.java`, `config/KiteProperties.java`, `resources/application.yml`
- Test: `broker/src/test/java/com/trading/bot/broker/config/KitePropertiesTest.java` (replaces SmokeTest)

**Interfaces:**
- Consumes: none.
- Produces (all later broker tasks):
  - `@SpringBootApplication TradingBotApplication` (main).
  - `@ConfigurationProperties("kite") @Validated KiteProperties` — fields: `String apiKey, userId, password, totpSecret; Http http; Auth auth; Orders orders;` nested: `Http(Duration connectTimeout=5s, Duration readTimeout=10s, Duration placeReadTimeout=15s)`, `Auth(Duration initialRetry=1m, Duration maxRetry=20m, int maxAttemptsPerHour=3)`, `Orders(Duration lostGrace=30s)`.
  - `application.yml`: Kite base URL `https://api.kite.trade`, scheduler pool size 4, all tunables as defaults; secrets unbound (env only).

- [ ] **Step 1: Failing tests** — (a) context with no `KITE_API_KEY` → fails, message contains exact var name `kite.api-key` / `KITE_API_KEY`; (b) blank TOTP secret → startup failure; (c) defaults present: 5s/10s/15s, 1m/20m/3, 30s; (d) secrets canary: context startup with all secrets set → captured logs contain none of the secret values.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `./gradlew :broker:test` → PASS.
- [ ] **Step 5: Commit** `feat(broker): boot skeleton + validated kite properties`.

### Task 6: TOTP generator

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/KiteTotp.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteTotpTest.java`

**Interfaces:**
- Produces (Task 8): `@Component KiteTotp { String code(String base32Secret, long epochSeconds); }` — counter = `epochSeconds / 30`, RFC 6238, 6-digit output via `dev.samstevens.totp.CodeGenerator` (wrapping its `generate(secret, counter)`), clock passed in (no `System.currentTimeMillis` inside).

- [ ] **Step 1: Failing tests** — RFC 6238 SHA-1 vectors, secret ASCII `12345678901234567890` (base32 `GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ`), 6-digit = last 6 of RFC 8-digit value: `(59, "287082")`, `(1111111109, "081804")`, `(1111111111, "050471")`, `(1234567890, "005924")`, `(2000000000, "279037")`, `(20000000000, "353130")`. Same epoch within one 30s step → identical code; different step → (assert not equal for 59 vs 89).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): rfc6238 totp generator`.

### Task 7: Sealed error hierarchy + domain model + status mapping

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/api/error/BrokerException.java` and siblings `AuthException`, `RateLimitException`, `NetworkException`, `AmbiguousOrderStateException`, `ApiException`
- Create: `broker/src/main/java/com/trading/bot/broker/api/model/` — `Broker.java` (interface), `OrderRequest.java`, `OrderPatch.java`, `OrderId.java`, `Order.java`, `OrderStatus.java`, `Position.java`, `Holding.java`, `Margins.java`, `Quote.java`, `InstrumentToken.java`, `TransactionType.java`, `OrderType.java`, `Product.java`, `Validity.java`, `Exchange.java`
- Test: `broker/src/test/java/com/trading/bot/broker/api/` — `ErrorHierarchyTest.java`, `DomainModelTest.java`, `OrderStatusMappingTest.java`

**Interfaces:**
- Produces (Tasks 8–14):
  - `public sealed class BrokerException extends RuntimeException permits AuthException, RateLimitException, NetworkException, AmbiguousOrderStateException, ApiException` — ctor `(String message, Throwable cause)`.
  - `AuthException(message,cause)`, `RateLimitException`, `NetworkException`; `AmbiguousOrderStateException(String operation, String correlationId, String detail, Throwable cause)` — fields `operation`, `correlationId` (the tag/order_id) exposed as getters; `ApiException(String errorType, int status, String kiteMessage, Throwable cause)`.
  - `Broker` interface (methods javadoc'd with thrown subtypes, per spec §6):
    ```java
    OrderId placeOrder(OrderRequest request);              // throws Auth, RateLimit, Network, Ambiguous, Api
    void modifyOrder(OrderId id, OrderPatch patch);        // same
    void cancelOrder(OrderId id);                          // same
    List<Order> orderBook();                               // Auth, RateLimit, Network, Api
    List<Position> positions();                            // same
    List<Holding> holdings();                              // same
    Margins margins();                                     // same
    Map<InstrumentToken, Quote> quotes(Collection<InstrumentToken> tokens); // same
    ```
  - Model pins: `record OrderRequest(InstrumentToken instrumentToken, String tradingsymbol, Exchange exchange, TransactionType transactionType, OrderType orderType, Product product, Validity validity, int quantity, Optional<BigDecimal> price, Optional<BigDecimal> triggerPrice, Optional<String> tag)` + compact-ctor validation (quantity > 0; LIMIT requires price; SL/SL-M require triggerPrice). `record OrderPatch(OptionalInt quantity, Optional<BigDecimal> price, Optional<BigDecimal> triggerPrice)`. `record OrderId(String value)`. `InstrumentToken(long value)`. Enums: `TransactionType{BUY,SELL}`, `OrderType{MARKET,LIMIT,SL,SL_M}` (wire value `SL M`), `Product{CNC,MIS,NRML}`, `Validity{DAY,IOC}`, `Exchange{NSE,NFO,BFO,MCX,CDS}` with `fromKite(String)`.
  - `enum OrderStatus { OPEN, PENDING, FILLED, PARTIAL, CANCELLED, REJECTED, EXPIRED, UNKNOWN; static OrderStatus fromKite(String raw); static boolean isTerminal(OrderStatus) }` — mapping table per spec §6 exactly (`TRIGGER PENDING→OPEN` with raw preserved by caller, four request-pending strings→PENDING, anything unknown→`UNKNOWN`, terminal = FILLED/CANCELLED/REJECTED/EXPIRED).
  - `Position(record)`: `(Exchange, String tradingsymbol, Product, int quantity, int overnight, int multiplier, BigDecimal averagePrice, BigDecimal pnl)`; `Holding`: `(Exchange, String tradingsymbol, int quantity, int t1Quantity, BigDecimal averagePrice, BigDecimal lastPrice, BigDecimal pnl)`; `Quote`: `(InstrumentToken token, String tradingsymbol, BigDecimal lastPrice, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume)`; `Margins`: `(Map<String,BigDecimal> funds, Map<String,Map<String,BigDecimal>> segments)`.

- [ ] **Step 1: Failing tests** — (a) `BrokerException` sealed: attempting an outside subclass is a compile-time property → instead assert every subtype instanceof + `AmbiguousOrderStateException` getters; (b) status table: one assertion per §6 row + unknown string `"SOME NEW STATUS"` → UNKNOWN + `isTerminal(UNKNOWN)==false`; (c) `OrderRequest` validation: LIMIT without price → IllegalArgumentException; quantity 0 → IAE; SL without trigger → IAE; (d) `OrderType.SL_M.toKite()=="SL M"`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): port interface, sealed errors, domain model`.

### Task 8: KiteSession — unattended login + drift measurement

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/KiteSession.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteSessionTest.java`

**Interfaces:**
- Consumes: Task 5 `KiteProperties`, Task 6 `KiteTotp`.
- Produces (Task 9, 10):
  - `@Component KiteSession` — `void login()`; `String accessToken()` (throws `AuthException` if absent/DEGRADED); `boolean isDegraded()`; drift measured from login response `Date` header vs `Clock`: drift >30s → WARN + publish `SYSTEM_ALERT` (via `NotificationPublisher`, title `Clock drift`).
  - Login: `POST /session/token` form `api_key, user_id, password, totp` (percent-encoded — never string-concatenated; use `LinkedHashMap` + client form encoding), success → `access_token` held in `volatile` field; failed login → `AuthException(kite error message)`.
  - Uses its own raw `RestClient` (timeouts from properties) — login runs before any token exists.

- [ ] **Step 1: Failing tests** (MockRestServiceServer) — (a) happy path: form on the wire contains `totp=<6 digits>` for frozen epoch + returns token; `accessToken()` serves it; (b) special-char password `p&ss=w0rd` → wire body matches `password=p%26ss%3Dw0rd` exactly (Review Focus 1); (c) error JSON 403 `{"status":"error","error_type":"TotpException","message":"Incorrect TOTP"}` → `AuthException` with that message, token stays null, `isDegraded()` true after login() failure? — no: DEGRADED is Task 10's state machine; here just exception + no token; (d) `Date` header 120s skewed → `SYSTEM_ALERT` published (assert via mock `NotificationPublisher`); (e) secrets canary: logs from a full login contain no password/totp/token.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): unattended kite login with drift check`.

### Task 9: KiteHttpClient — transport, error mapping, ambiguity, re-auth replay

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/KiteHttpClient.java` and `broker/src/main/java/com/trading/bot/broker/kite/dto/` (`LoginResponseDto`, `OrderBookDto`, `OrderDto`, `PositionDto`, `HoldingDto`, `MarginsDto`, `QuoteDto`, `QuoteDataDto`, `PlaceOrderResponseDto`, `ErrorDto`)
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteHttpClientTest.java`

**Interfaces:**
- Consumes: Task 5 properties, Task 7 errors, Task 8 `KiteSession` (token + `login()` for replay).
- Produces (Tasks 10, 11, 13):
  ```java
  interface SessionDelegate { String accessToken(); void forceRelogin(); }   // implemented by SessionRefresher (Task 10); KiteSession is the no-replay login path in this task
  enum Mutability { READ, MUTATION }
  @Component KiteHttpClient
    KiteHttpClient(KiteProperties props, SessionDelegate delegate, RestClient.Builder builder)
    <T> T get(String path, Class<T> responseType, Mutability m)
    <T> T post(String path, Map<String,String> form, Class<T> responseType, Mutability m)
  ```
  - Headers on auth'd calls: `X-Kite-Version: 3`, Basic `apiKey:accessToken`. Timeouts: connect 5s; read 10s (READ) / 15s (MUTATION).
  - Error mapping (single choke point, nothing else escapes): Kite error JSON → `RateLimitException` (429), `AuthException` (401/403 TokenException family) → **one** replay: `delegate.forceRelogin()` then retry exactly once, else propagate; non-JSON/malformed body → `ApiException("UnparseableBody", status, first 200 chars sanitized, cause)` (Review Focus 2); **MUTATION failure after request bytes sent (timeout/reset/5xx) → `AmbiguousOrderStateException(operation, form tag/order_id, detail, cause)`; READ timeout → `NetworkException`** (spec §7 rule). Success body `{"status":"success","data":...}` → DTO.
  - `operation` for ambiguity: pass path as operation (form carries `tag` — extract for the correlation id).

- [ ] **Step 1: Failing tests** (MockRestServiceServer) — (a) happy GET returns parsed DTO with auth headers present; (b) 429 → RateLimitException; (c) TokenException → forceRelogin called once → replay succeeds → returns data; TokenException again → AuthException, exactly 2 total requests; (d) place-order connection-reset / 500 / read-timeout (simulate via server never responding + short timeout) → AmbiguousOrderStateException, correlationId == form's `tag`, **zero retries** (request count == 1); (e) READ 500/timeout → NetworkException/ApiException, never Ambiguous; (f) HTML 502 body → ApiException, no Jackson type in thrown chain (walk `cause` chain asserting only BrokerException+IOException/SocketTimeoutException); (g) timeout config: client factory carries 5s/10s/15s (ReflectionTestUtils or builder spy).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): kite http client with ambiguity rule + one-shot re-auth`.

### Task 10: SessionRefresher — cron refresh + DEGRADED state machine

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/SessionRefresher.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/SessionRefresherTest.java`

**Interfaces:**
- Consumes: Task 8 `KiteSession`, Task 9 `SessionDelegate`, Task 5 `KiteProperties.Auth`.
- Produces: `@Component SessionRefresher implements SessionDelegate`.
  - `@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Kolkata") void scheduledRefresh()` → `kite.reauth.attempts` + `KiteSession.login()`.
  - `String accessToken()` → delegates to session.
  - `void forceRelogin()` (called by Task 9 replay): records attempt times; backoff schedule 1m, 2m, 4m … capped 20m; **hard cap `maxAttemptsPerHour=3` in rolling 60-min window → when exhausted, throw `AuthException("DEGRADED...")` WITHOUT calling login**; on failure publish CRITICAL `AUTH_DEGRADED` (attempt #, local ISO timestamp, drift); on success reset counters + publish `AUTH_RECOVERED` + mark not-degraded. `boolean isDegraded()`.
  - All time via injected `Clock` (IST semantics don't matter for rolling windows; cron zone fixed).

- [ ] **Step 1: Failing tests** (fixed Clock, no sleeps) — (a) failures at t0,t1m,t3m → attempts fire at 0/60/180s schedule points (1m then 2m), success at 4th → AUTH_RECOVERED, degraded false; (b) exactly 3 attempts inside an hour → 4th `forceRelogin` throws DEGRADED AuthException with **zero** `KiteSession.login()` invocations (spy) — never a 4th login (Review Focus: account lock); (c) at t0+61min the window rolls → attempts allowed again; (d) AUTH_DEGRADED notification carries attempt number + timestamp text.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): session refresher with capped backoff + degraded state`.

### Task 11: KiteBroker adapter — order ops + tags + submit events

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/KiteBroker.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/KiteBrokerTest.java`

**Interfaces:**
- Consumes: Task 7 `Broker` + model, Task 9 client, Task 2 publisher.
- Produces: `@Component KiteBroker implements Broker`.
  - `placeOrder`: if `request.tag()` empty → auto-generate tag = `"t" + epochMillis36 + "-" + shortUUID` trimmed to ≤20 alnum/hyphen (Kite accepts alphanumeric — use only `[a-z0-9]` + strip hyphen if needed; pin: `Long.toString(epochMillis,36) + randomBase36(6)`, ≤20); wire form includes `tag`; on success publish `ORDER_SUBMITTED` (INFO: side/symbol/qty/tag/order_id) and return `OrderId`; on `AmbiguousOrderStateException` publish `ORDER_AMBIGUOUS` (CRITICAL) and rethrow.
  - `modifyOrder`/`cancelOrder`: form with `order_id`; ambiguity handled by Task 9 — adapter publishes nothing on ambiguity (state unconfirmed), rethrows.
  - Read ops map DTOs → domain using `OrderStatus.fromKite`.
- Pin Kite wire paths: `POST /orders/normals`, `PUT /orders/{orderId}`, `DELETE /orders/{orderId}`, `GET /orders`, `GET /portfolio/positions`, `GET /portfolio/holdings`, `GET /user/margins`, `GET /quote` (`i=NSE:RELIANCE` style keys built from token? Kite /quote uses `exchange:tradingsymbol` keys — pin: keys built from `Exchange + ":" + tradingsymbol` passed by caller via `Quote` inputs? — **Decision: `quotes` takes `Collection<InstrumentToken>` but Kite /quote needs exchange:symbol; therefore `quotes` uses `GET /quote` with keys supplied through a `QuoteKey` resolver** → simplify: **`Map<InstrumentToken,Quote> quotes(Collection<QuoteRef>)` where `record QuoteRef(Exchange exchange, String tradingsymbol, InstrumentToken token)`** — update `Broker.quotes` signature here and in Task 7 file at this task (Modify Task 7's `Broker.java`).

- [ ] **Step 1: Failing tests** (MockRestServiceServer + mock publisher) — (a) place happy: wire form has exchange/tradingsymbol/transaction_type/order_type/quantity/product/validity/tag; returns OrderId; `ORDER_SUBMITTED` published once with tag in body; (b) **100 places in the same second → 100 distinct tags, all ≤20 chars, all `[a-z0-9]+`** (Review Focus 3); (c) caller-supplied tag is used verbatim (never replaced); (d) ambiguous throw → `ORDER_AMBIGUOUS` CRITICAL published, exception rethrown unchanged (same correlationId); (e) `orderBook()` maps every §6 status row from fixture JSON; (f) `positions()`/`holdings()`/`margins()`/`quotes()` map fixture → domain records field-exact.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces (incl. the `Broker.quotes` signature fix).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): kite adapter — order ops, auto-tags, submit events`.

### Task 12: ExecutionTracker — state machine, exactly-once, ambiguity resolution

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/ExecutionTracker.java`, `broker/src/main/java/com/trading/bot/broker/kite/TrackedOrder.java`, `broker/src/main/java/com/trading/bot/broker/kite/OrderState.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/ExecutionTrackerTest.java`

**Interfaces:**
- Consumes: Task 2 publisher, Task 7 model, metrics registry.
- Produces (Task 13 consumes):
  ```java
  enum OrderState { SUBMITTED, OPEN, PARTIAL, FILLED, CANCELLED, REJECTED, EXPIRED, LOST, FAILED }
  record TrackedOrder(OrderId id, String tag, OrderState state, int quantity, int filled, String reason, Instant submittedAt)
  @Component ExecutionTracker(NotificationPublisher publisher, MeterRegistry registry, Clock clock)
    void register(OrderId id, String tag, OrderRequest request)          // -> state SUBMITTED
    boolean observe(OrderSnapshot snapshot)                               // CAS; returns stateChanged; emits event exactly once per (id,state)
    void declareLost(OrderId id)                                          // Reconciler-only entry point -> LOST + ORDER_LOST + tracker.orders.lost counter
    Optional<TrackedOrder> byTag(String tag)
    List<TrackedOrder> openOrders()
    void resolveAmbiguity(String correlationId, OrderId found)            // AMBIGUOUS -> SUBMITTED (publishes); caller handles not-found + grace separately in Task 13
  record OrderSnapshot(OrderId id, OrderStatus status, int quantity, int filled, String reason, Instant observedAt)
  ```
  - Transitions per spec §7 diagram; illegal transitions (e.g. FILLED→OPEN) are ignored + WARN (never exception — poller data races must not kill the poller). PARTIAL derived: `filled>0 && filled<quantity && !terminal`. Only `FILLED` (from Kite `COMPLETE`) emits `ORDER_FILLED`; `REJECTED`/`CANCELLED`/`EXPIRED` emit their types with `reason`; emissions carry tag, symbol (kept on register), side, qty, filled.
  - `register` starts `kite.order.latency` timer; first positive observation stops it.
  - Thread-safety: per-order lock or synchronized on `id` — test hammers from 8 threads.

- [ ] **Step 1: Failing tests** — (a) register→OPEN→PARTIAL→FILLED sequence publishes exactly 1 event each of SUBMITTED(handled at adapter), PARTIAL, FILLED; (b) **exactly-once: same FILLED snapshot delivered concurrently from 2 threads × 50 iterations → exactly 1 `ORDER_FILLED` published total** (AtomicInteger on mock publisher); (c) illegal FILLED→OPEN ignored + no event; (d) REJECTED carries Kite reason string; (e) `declareLost` publishes CRITICAL `ORDER_LOST` + counter; (f) `resolveAmbiguity(found)` → SUBMITTED + one event; (g) alert-only: tracker never invokes anything resembling place/resubmit (verify publisher-only interaction); (h) burst: 8 threads × 200 mixed snapshots → final states consistent, total events == distinct (id,state) pairs.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): execution tracker with exactly-once transitions`.

### Task 13: MarketHours, OrderPoller, Reconciler + startup rebuild

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/kite/MarketHours.java`, `OrderPoller.java`, `Reconciler.java`
- Test: `broker/src/test/java/com/trading/bot/broker/kite/MarketHoursTest.java`, `OrderPollerTest.java`, `ReconcilerTest.java`

**Interfaces:**
- Consumes: Task 7 `Broker`, Task 12 tracker, Task 2 publisher, Task 5 `Orders.lostGrace`, `RuntimeSignals` (create in this task: `broker/src/main/java/com/trading/bot/broker/health/RuntimeSignals.java` — `volatile long lastPollerRun,lastReconcilerRun` epoch sec, `markPollerRun()`, `markReconcilerRun()`; Task 15 reads it).
- Produces:
  - `MarketHours.isOpen(Instant, ZoneId)` — Mon–Fri 09:15 ≤ t < 15:30 IST. (No holiday calendar — non-goal.)
  - `@Component OrderPoller` — `@Scheduled(fixedDelayString = "5000")`: if `!MarketHours.isOpen` → return silently; else `broker.orderBook()`, for each order observed → `tracker.observe(snapshot)`; errors → catch at boundary: ERROR log with context + `kite.poller.errors` counter + `markPollerRun` only on successful run.
  - `@Component Reconciler` — `@Scheduled(fixedDelayString = "30000")` same market gate; LOST rule: track per-order consecutive-miss count + last positive observation Instant (map in Reconciler, keyed by OrderId); declare LOST only when `misses ≥ 2 && now − lastPositive ≥ lostGrace`; also `@EventListener(ApplicationReadyEvent) void rebuildOnStartup()`: fetch order book, `register()` any non-terminal order NOT already tracked **silently** (no ORDER_SUBMITTED), then publish one grouped `ORDER_RECOVERED` (list of order ids) — if none, publish nothing; ambiguous orders found by tag → `resolveAmbiguity`.
  - Reconciler observes too (drives FILLED if poller missed) — both call `observe`, tracker's CAS makes it idempotent.

- [ ] **Step 1: Failing tests** — (a) `MarketHours`: Sat/Sun false; Fri 15:30 IST false; Fri 15:29:59 true; Mon 09:14 false; Mon 09:15 true; UTC-now equivalent asserts zone-independence (Review Focus 4); (b) poller: market closed → zero `orderBook()` calls (mock verify no interactions); open → snapshots passed to tracker, `markPollerRun` set; broker throws → ERROR logged, counter++, **next invocation still runs** (call twice); (c) reconciler: single miss → no LOST; 2 misses but grace not elapsed → no LOST; 2 misses + 31s → `declareLost` once; fill observed between poller runs → not lost, FILLED exactly once (tracker CAS already tested — here assert handoff); (d) startup rebuild: fixture book has 3 open orders (1 already tracked pre-restart) → register 2 silently, **total ORDER_SUBMITTED-equivalent events == 0**, one `ORDER_RECOVERED` listing both; empty book → no RECOVERED; ambiguous tagged order → resolveAmbiguity called.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): poller, reconciler with double-miss LOST rule, startup rebuild`.

### Task 14: StartupReporter — positions handshake

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/startup/StartupReporter.java`
- Test: `broker/src/test/java/com/trading/bot/broker/startup/StartupReporterTest.java`

**Interfaces:**
- Consumes: Task 7 `Broker.positions()`, Task 2 publisher.
- Produces: `@Component StartupReporter` — `@EventListener(ApplicationReadyEvent)` (order `100`, after rebuild): fetch positions → publish **one** `STARTUP` (INFO) whose body lists each position `SYMBOL side qty qty@avg pnl=P&L` (INR formatted), or exact text `no open positions` when flat; on exception → publish `STARTUP` with `positions unavailable: <reason>` (still exactly one STARTUP) — catch boundary #2, ERROR log too. SHUTDOWN published on `@PreDestroy`.

- [ ] **Step 1: Failing tests** — (a) 2 fixture positions → exactly 1 STARTUP containing both symbols + pnl; (b) empty → contains "no open positions"; (c) positions() throws AuthException → exactly 1 STARTUP containing "positions unavailable" + exception message, no rethrow; (d) two rapid ApplicationReady events (test double-fire) → still emits per event (Spring fires once — assert handler doesn't double-publish within one event: capture count == 1).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(broker): startup positions handshake`.

### Task 15: Health indicator + metrics wiring

**Files:**
- Create: `broker/src/main/java/com/trading/bot/broker/health/TradingHealthIndicator.java`
- Modify: `SessionRefresher` (expose `isDegraded`), `TelegramSender` (expose `consecutiveFailures()`), `RuntimeSignals` (add send-failure/success marks if not in Task 13)
- Test: `broker/src/test/java/com/trading/bot/broker/health/TradingHealthIndicatorTest.java`

**Interfaces:**
- Consumes: Tasks 10, 13, 15 runtime signals, actuator.
- Produces: `@Component TradingHealthIndicator implements HealthIndicator` — component `kiteAuth` (UP / DOWN when `SessionRefresher.isDegraded()`), `notifications` (DOWN after `consecutiveFailures ≥ 3`), `schedulers` (DOWN when `now − lastPollerRun > 60s` **and** market open; UP/unknown when market closed). Overall status = worst. Metrics verified present: `notifications.dropped`, `notifications.send.failures`, `kite.reauth.attempts`, `kite.order.latency`, `kite.poller.errors`, `tracker.orders.lost`.

- [ ] **Step 1: Failing tests** — degraded refresher → health DOWN with `kiteAuth` detail; telegram 3 consecutive failures → `notifications` DOWN; poller stale >60s during market hours → `schedulers` DOWN, outside hours → UP; registry contains all six metric names (query `MeterRegistry.find`).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** → PASS; **full** `./gradlew build` green (all gates).
- [ ] **Step 5: Commit** `feat(broker): health indicator + metrics`.

### Task 16: Docker, compose, smoke doc

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `.env.example`, `docs/smoke.md`

**Interfaces:**
- Consumes: Task 1 build (boot jar from `broker`), spec §10.
- Produces:
  - `Dockerfile`: stage 1 `gradle:9-jdk25` → `./gradlew :broker:bootJar` (+ `build` for gates); stage 2 `eclipse-temurin:25-jre`, non-root `useradd`, copy jar, `EXPOSE 8080`, `HEALTHCHECK` → `http://localhost:8080/actuator/health` via `curl`/wget (install minimal), `ENTRYPOINT java -jar`.
  - `docker-compose.yml`: service `trading-bot`, `env_file: .env`, `restart: unless-stopped`, `healthcheck` mirroring Dockerfile, ports `127.0.0.1:8080:8080`.
  - `.env.example`: all six secret vars + LOG_LEVEL placeholders (no real values).
  - `docs/smoke.md`: profile-gated real-run checklist — set `.env`, `docker compose up`, expect exactly one STARTUP positions message on Telegram, then `curl localhost:8080/actuator/health` UP; never run in CI.

- [ ] **Step 1: Write** the four files per Interfaces.
- [ ] **Step 2: Verify** `docker build .` succeeds; `docker compose config` validates; `./gradlew build` still green.
- [ ] **Step 3: Commit** `chore: docker packaging + smoke checklist`.
- [ ] **Step 4: Final verification** Run: `./gradlew build` → BUILD SUCCESSFUL with all quality gates; `git log --oneline` shows one commit per task.

---

## Self-review notes (fixes applied during writing)

- Task 11 forced a signature decision: Kite `/quote` needs `exchange:symbol` keys, so `Broker.quotes` takes `Collection<QuoteRef>` (token + exchange + tradingsymbol) instead of bare tokens — Task 7 defines `QuoteRef`, Task 11 consumes it. (Original spec said `InstrumentToken` collection; this is the minimal correction, noted here rather than silently.)
- `RuntimeSignals` created in Task 13 (its first consumer), extended in Task 15 — not a separate scaffold task.
- Coverage check: spec G1→T8, G2→T11, G3→T7, G4→T9/T10, G5→T1, G6→T2–4, G7→T12/T13, G8→T15; §7 rules→T9/T10/T12/T13; §8→T5/T4; acceptance scenarios 1–8→T13/T14/T12/T10/T4/T13/T5/T14. No gaps found.
