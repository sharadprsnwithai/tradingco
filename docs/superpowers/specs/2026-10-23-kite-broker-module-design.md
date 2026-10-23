# Broker + Notification Modules — Design Spec

Date: 2026-10-23 (rev 2 — test-engineering gap review folded in)
Status: Draft, awaiting user approval
Scope: First two modules of an automated trading system: a headless broker
module that logs into Zerodha Kite Connect fully unattended (API key +
password + automated TOTP), and a separate notification module that streams
every order event and alert to Telegram through a single in-process flux.
Includes the signal-to-trade verification machinery the future strategy will
rely on.

## 1. Intent

The user runs an automated trading system end-to-end in a Docker container with
no human in the loop. The broker integration is the foundation: every trade the
future strategy makes goes through it. The user has a Zerodha Kite Connect
subscription (api_key/api_secret from the developer portal) and wants the TOTP
second factor generated programmatically from the base32 seed, so login never
needs a phone or a browser.

Second, the user wants trustworthy visibility: every order submission, failure,
fill, and degradation is pushed to Telegram through one notification flux —
whatever enters the flux is delivered. And critically: the system must prove
that a generated signal was actually traded at Kite, not merely assume it.

## 2. Goals

- G1: Unattended login: api_key + user_id + password + TOTP seed → access token,
  entirely in-process, inside a container with no browser and no phone.
- G2: Core trading operations over the Kite REST API: place / modify / cancel
  order, order book, positions, holdings, margins, and LTP/quote lookups.
- G3: Clean port for the strategy layer: the strategy will depend on a
  `Broker` interface (domain-owned), not on Kite. Kite is one adapter
  implementing that port. (Screams at the seams — SOLID: DIP, SRP per class.)
- G4: Production behaviors that matter for money: transparent re-authentication
  when the daily session expires, explicit surfacing of ambiguous order states
  after network timeouts, no secrets in logs, structured error types.
- G5: Java 25 (records, sealed types, virtual threads), Spring Boot 4.1.x,
  Lombok to kill boilerplate, Gradle 9 (Kotlin DSL), runs in Docker.
- G6: Notification flux: a separate `notifications` module exposing one
  `publish(Notification)` entry point backed by a Reactor `Flux` pipeline that
  delivers to Telegram; all order lifecycle events and system alerts flow
  through it. Nothing else talks to Telegram.
- G7: Signal-to-trade verification: every order is tracked as a state machine
  against Kite's order book as the only source of truth; FILLED / REJECTED /
  LOST are proven, never assumed, and surfaced on the flux.
- G8: The system's health is externally verifiable: Docker's healthcheck goes
  red when the system cannot trade or cannot alert, not just when the JVM is
  up; key counters (drops, re-auths, latency) are exposed as metrics.

## 3. Non-goals (v1)

- Strategy logic itself — only the `Broker` seam it will plug into later.
- WebSocket live tick streaming (KiteTicker) — strategy is not built yet; REST
  LTP/quotes cover the first need; order updates use the order-book poller.
- Multi-broker support — the port makes it possible later; we build only Kite.
- Backtesting, persistence/database, order history storage, UI, proactive
  rate-limit throttling, retry-on-timeout for order placement.
- **Auto-resubmission of failed/lost orders (user decision: alert-only).**
  The tracker marks the signal FAILED and alerts Telegram; the future strategy
  decides whether to re-signal. Risk of duplicate orders from a wrong LOST
  diagnosis is deliberately not taken.
- Separate deployable/notification service + message broker — in-process flux
  only (user decision); the port keeps that door open.
- Spring Security / auth on the web surface — the app exposes only actuator
  health + metrics; it binds nothing sensitive to a port (or binds to
  localhost only).
- An NTP client dependency — clock-drift detection reuses the Kite HTTP
  response's `Date` header instead (§5-D14); no new infrastructure.

## 4. Constraints & external facts

- Kite Connect REST API base: `https://api.kite.trade`.
- Login flow (the only non-obvious part):
  1. `POST /session/token` form-encoded: `api_key`, `user_id`, `password`,
     `totp` → JSON body contains `access_token` (also `enctoken`, not needed).
  2. Access token is valid until 06:00 IST the next calendar day (actually the
     next trading day; we conservatively re-login at 06:00 IST daily and on any
     `TokenException`).
- All requests except login carry `X-Kite-Version: 3` header and auth as
  `api_key:access_token` in HTTP Basic.
- Errors come back as HTTP 4xx/5xx JSON:
  `{"status":"error","error_type":"TokenException","message":"..."}`. We map
  `error_type` to our sealed error hierarchy.
- **Kite order status strings (exhaustive mapping required — §6):**
  `OPEN`, `TRIGGER PENDING`, `COMPLETE`, `CANCELLED`, `REJECTED`, `EXPIRED`,
  plus transient request states (`OPEN PENDING`, `PUT ORDER REQ RECEIVED`,
  `MODIFY REQ RECEIVED`, `CANCEL REQ RECEIVED`). Partial fill is NOT a status
  — it is derived: `0 < filled_quantity < quantity` on a non-terminal order.
  An unknown/unmapped string must never crash the poller (§5-D14 rule).
- Rate limits (per Kite docs): order place/modify/cancel bursts and per-second
  caps (e.g. 10/s for orders, 50/s for other REST); exceeding returns 429. v1
  surfaces `RateLimitException`; proactive throttling is deferred (§3).
- Orders are only confirmed by `GET /orders` (order book) — the place-order
  response is submission ack only; that's the premise of §7.
- **HTTP defaults are unbounded:** Spring's request factories hang forever
  without explicit timeouts — every client timeout is explicit config (§8).
- Telegram Bot API: plain HTTPS `POST /bot<token>/sendMessage`; **max 4096
  chars per message; ~30 msgs/min per chat** — sender chunks and paces (§5-D11);
  no library needed, direct RestClient calls.
- User credentials needed unattended: `KITE_API_KEY`, `KITE_USER_ID`,
  `KITE_PASSWORD`, `KITE_TOTP_SECRET` (base32 seed from Kite account settings →
  TOTP section), `TELEGRAM_BOT_TOKEN` (BotFather), `TELEGRAM_CHAT_ID`. All
  supplied as environment variables, bound by Spring config properties; never
  written to disk by the app, never logged.

## 5. Design decisions (the how, and why)

- D1 — **Spring `RestClient` (spring-web) for HTTP + Jackson for JSON.**
  Spring Boot is the platform now, so its HTTP client and ObjectMapper are used
  rather than raw `java.net.http` — less glue, consistent error handling.
  Jackson annotations live only on package-private Kite DTOs inside the
  adapter. **Explicit timeouts (defaults are unbounded): connect 5s, read 10s
  (15s for order placement); all three are config properties (§8).** A read
  timeout on place/modify/cancel is an ambiguity trigger, not a retry trigger
  (§7).
- D2 — **TOTP via `dev.samstevens.totp` (tiny, RFC 6238).** Hand-rolling HMAC
  TOTP is 25 lines, but it is security-sensitive code and base32 decoding bugs
  are painful to debug. A small established lib wins.
- D3 — **Re-login at startup + scheduled re-login before 06:00 IST expiry +
  reactive re-login on `TokenException`.** `SessionRefresher` owns the access
  token and is driven by Spring `@Scheduled(cron = ..., zone = "Asia/Kolkata")`;
  the adapter calls it and transparently retries the request once after re-auth
  (never for non-idempotent mutations on timeout — see §7).
- D4 — **Port & adapter.** `broker.api.Broker` is the domain port;
  `broker.kite.KiteBroker` is the adapter, a Spring `@Component` implementing
  the port. Future broker (or a paper-trading fake for tests/strategy dev)
  implements the same port. The strategy injects `Broker`, never `KiteBroker`.
- D5 — **Domain types are plain records/enums/sealed interfaces owned by the
  port package — no Lombok there.** Records already express immutable domain
  data with zero boilerplate; Lombok earns its keep in the adapter/config/
  infrastructure classes (`@RequiredArgsConstructor`, `@Slf4j`) where it kills
  constructors and logger boilerplate. Jackson never escapes the adapter.
- D6 — **Gradle multi-module from day one, two subprojects:** `broker`
  (the runnable Spring Boot app) and `notifications` (library module, no
  main). `broker` depends on `notifications`. Converting a single module later
  would churn every package — two modules now is the honest minimum for
  "separate module", and no structure beyond that is built ahead of need.
- D7 — **Virtual threads for the small amount of concurrency that exists**
  (e.g., fetching margins + positions in parallel); the client is synchronous
  and thread-safe (token reads via holder/`volatile`).
- D8 — **Logging via Spring Boot default (SLF4J + Logback) + Lombok `@Slf4j`;
  secrets masked.** Access tokens, passwords, and bot token never logged;
  token shown as `ak_****last4`. A dedicated canary test proves it (§9).
- D9 — **Docker: multi-stage build.** Stage 1 `gradle:9-jdk25` builds; stage 2
  `eclipse-temurin:25-jre`, non-root user, `docker-compose.yml` with `.env`
  file for secrets, compose healthcheck against `/actuator/health` (now
  meaningful — see D15). App runs in foreground, restart `unless-stopped`.
  Notifications run in this same container/process (user decision).
- D10 — **Spring Boot 4.1.x (current stable, Java 25 first-class since 4.0;
  4.0.x goes EOL Dec 2026, 4.1.x supported to Jul 2027). Lombok 1.18.48**
  (JDK 25 support landed in 1.18.40; pin latest). Config binding via
  `@ConfigurationProperties` with startup validation (fail-fast, clear error
  naming the missing variable). Actuator exposes health + metrics only.
- D11 — **Notification flux = two lanes feeding Telegram, both from one
  `publish(Notification)` entry point** (reactor-core, version managed by
  Boot):
  - **Normal lane:** `Sinks.Many` → Flux → enrich → micro-batch (flush on
    **1s window OR 50 items, whichever first**) → paced sender.
  - **CRITICAL lane:** bypasses batching entirely — a separate sink consumed
    with immediate flush, so a critical alert never queues behind a batch or a
    retry backoff. Pacing still applies (shared token bucket, ~28 msg/min
    budget under Telegram's ~30/min cap); if the bucket is empty, CRITICAL
    waits on the bucket only — never on the normal lane.
  - **Sender:** chunks bodies at 4096 chars (truncation marker included),
    capped retries (3, exponential within the pacing budget), then ERROR-log
    fallback and `notifications.send.failures` increment.
  - **Non-negotiable (tested):** bounded buffer (default 1000),
    `tryEmitNext` best-effort — on overflow drop-oldest + WARN +
    `notifications.dropped` counter. A Telegram outage or slow network must
    never block or fail trading.
- D12 — **Order-book poller + `ExecutionTracker` + reconciler live in the
  broker module** (they need Kite access; the notification module only
  delivers). Poller runs ~5s during market hours (09:15–15:30 IST, Mon–Fri),
  diffs status transitions, and only order-book state can move an order to
  FILLED — the place-order response proves nothing. **LOST rules (tested):**
  only the Reconciler may declare LOST, never the poller; requires ≥2
  consecutive observation misses AND ≥ `kite.orders.lost-grace` (default 30s)
  since last observed/submitted; grace starts from the last *positive*
  observation, so a placement→visibility race can never false-LOST (§7).
- D13 — **Alert-only on failure (user decision):** REJECTED/LOST transitions
  mark the signal FAILED (with Kite's reason) and publish to the flux; no
  automatic resubmission ever.
- D14 — **Clock-drift guard + unmapped-status guard (test-review additions):**
  - Every Kite HTTP response's `Date` header is compared to system time (no
    NTP dependency); startup and each re-login record drift; drift > 30s logs
    WARN and fires a SYSTEM_ALERT — because TOTP fails at ±30–60s and drift
    would otherwise masquerade as a generic login failure. Every failed-login
    CRITICAL alert includes the local ISO timestamp + measured drift, so the
    operator can diagnose in one glance.
  - Unknown order-status string: poller maps to `OrderStatus.UNKNOWN`,
    keeps the order's last known good state, fires a CRITICAL alert with the
    raw string. Never crashes the poller, never advances state on
    speculation. (Fail-safe, not fail-silent — my call as designer: a new
    Kite status must not halt tracking, and must not be guessed at either.)
- D15 — **Health + metrics for an externally verifiable system:**
  - `TradingHealthIndicator` (Spring `HealthIndicator`) reports: auth state
    (UP / DEGRADED), notification pipeline (UP / DEGRADED after N consecutive
    send failures), poller/reconciler last-run freshness. Docker's
    healthcheck hits `/actuator/health` — it now goes red when the system
    cannot trade or cannot alert, not just when the JVM is alive.
  - Micrometer metrics (Boot-managed, no new deps): `notifications.dropped`,
    `notifications.send.failures`, `kite.reauth.attempts`,
    `kite.order.latency` (timer, submit→first observation),
    `kite.poller.errors`, `tracker.orders.lost`.

## 6. Module structure (packages, one job each)

```
settings.gradle.kts        # includes: broker, notifications
broker/                    # runnable Spring Boot app
  TradingBotApplication.java   # Spring Boot main
  api/            # the port — what the strategy will import
    Broker.java           # interface: placeOrder, modifyOrder, cancelOrder,
                          # orderBook, positions, holdings, margins, quotes;
                          # every method javadoc'd with the BrokerException
                          # subtypes it can throw (they're unchecked — the
                          # contract must be written down)
    model/        # records & enums: OrderRequest, OrderId, Order, OrderStatus,
                          # Position, Holding, Margin, Quote, InstrumentToken,
                          # TransactionType, OrderType, Product, Validity, ...
    OrderStatus.java      # exhaustive enum + UNKNOWN; mapping table below
    error/        # sealed BrokerException: AuthException, RateLimitException,
                          # NetworkException, AmbiguousOrderStateException,
                          # ApiException(errorType, code, message)
  kite/           # adapter — Kite-specific, hidden from the port
    KiteBroker.java       # @Component implements Broker; maps DTOs <-> domain,
                          # publishes lifecycle events to the flux
    KiteHttpClient.java   # RestClient wrapper: explicit timeouts, auth headers,
                          # error -> BrokerException mapping, one-shot re-auth
                          # retry (never for mutations in flight — §7)
    KiteSession.java      # login + access token lifecycle (TOTP call here),
                          # Date-header drift measurement
    SessionRefresher.java # @Scheduled refresh before 06:00 IST + on-demand,
                          # DEGRADED state + exact capped backoff (§7)
    OrderPoller.java      # @Scheduled order-book diff during market hours;
                          # observational only — never declares LOST
    ExecutionTracker.java # in-memory state machine; exactly-once transition
                          # emission (CAS per orderId+state); thread-safe
    Reconciler.java       # @Scheduled tracker-vs-order-book sweep; sole
                          # authority for LOST; startup rebuild of open orders
    dto/          # package-private Jackson DTOs for Kite JSON responses
  health/
    TradingHealthIndicator.java # auth + notification pipeline + scheduler
                          # freshness -> /actuator/health
  config/
    KiteProperties.java    # @ConfigurationProperties("kite"), validated at
                           # startup; fails fast naming the missing/invalid var
notifications/            # library module, no main
  api/
    NotificationPublisher.java  # the one entry point: publish(Notification)
    Notification.java           # record: severity, type, title, body, cause, ts
    Severity.java               # INFO, WARN, CRITICAL (CRITICAL = lane bypass)
    NotificationType.java       # exhaustive taxonomy (below)
  internal/ (package-private)
    NotificationFlux.java # @Component: two-lane Sinks -> Flux pipeline
                          # (batching, bounded buffer, drop-oldest, metrics)
    TelegramSender.java   # RestClient -> Bot API; 4096 chunking, paced
                          # (~28/min), capped retries, log fallback
    TelegramProperties.java # @ConfigurationProperties("telegram")
```

**Order-status mapping (exhaustive — every row is a test case):**

| Kite status string | Domain `OrderStatus` |
|---|---|
| `OPEN` | `OPEN` |
| `TRIGGER PENDING` | `OPEN` (raw status preserved for display) |
| `COMPLETE` | `FILLED` (partial info is derived, not a status) |
| `CANCELLED` | `CANCELLED` |
| `REJECTED` | `REJECTED` |
| `EXPIRED` | `EXPIRED` |
| `OPEN PENDING`, `PUT ORDER REQ RECEIVED`, `MODIFY REQ RECEIVED`, `CANCEL REQ RECEIVED` | `PENDING` (transient) |
| anything else | `UNKNOWN` + CRITICAL alert (D14) |

**NotificationType taxonomy (exhaustive):** `STARTUP` (one per container
start: app up + logged in + **current positions listed, or "no open
positions", or positions-unavailable + reason**), `SHUTDOWN`,
`ORDER_SUBMITTED`, `ORDER_PARTIAL` (emitted on each filled-quantity change,
batches merge bursts), `ORDER_FILLED`, `ORDER_REJECTED`, `ORDER_CANCELLED`,
`ORDER_LOST`, `ORDER_AMBIGUOUS`, `ORDER_RECOVERED` (startup rebuild, one
grouped notice), `AUTH_DEGRADED`, `AUTH_RECOVERED`, `SYSTEM_ALERT`
(validation, drift, unmapped status).

SOLID mapping: SRP — each class does one thing (session, HTTP, mapping,
scheduling, polling, tracking, reconciling, health, config, delivering); OCP —
new broker or new notification channel = new package, ports untouched; LSP —
one implementation of `Broker` today, fake in tests; ISP — both ports are one
cohesive method-set each; DIP — strategy depends on `Broker` +
`NotificationPublisher`, never on Kite or Telegram classes.

## 7. Error handling & the money-safety rules

- Kite error JSON → sealed `BrokerException` subclass. Unknown `error_type`
  falls into `ApiException` with the raw type preserved.
- **Exception-handling contract (user requirement — debuggability):**
  - **Callers of `Broker` only ever see the sealed hierarchy.**
    `KiteHttpClient` is the single choke point: any unexpected throwable
    (Jackson parse error, RestClient failure, NPE) is wrapped there into a
    `BrokerException` subtype — a raw exception must never escape the adapter
    (tested by feeding malformed/empty/truncated responses to every endpoint).
  - **Every exception carries debug context:** operation name
    (`placeOrder`), endpoint, HTTP status, Kite `error_type`/code/message,
    correlation `tag`/`order_id`, timestamp. Exception messages are sanitized
    by the same secrets rule as logs — password/TOTP/token never appear in
    any exception message or cause chain (tested).
  - **Never swallowed, logged once:** causes are always chained; an exception
    is logged at the boundary that *handles* it, not re-logged at each stack
    frame — one failure = one structured ERROR log containing that context.
  - **No blanket try/catch per method.** Catching lives at exactly two
    boundaries: the HTTP client (wraps into the hierarchy) and scheduled-task
    runners (Reconciler/OrderPoller/SessionRefresher catch their own failures
    → structured ERROR with context + `kite.poller.errors`/metric increment +
    DEGRADED/health signal if persistent → next scheduled run proceeds.
    One bad run never kills a loop, and `@Scheduled` methods never propagate
    to kill the scheduler.
- **Re-auth retry:** on `AuthException` (401/403 TokenException) the adapter
  forces re-login and replays the request exactly once, then propagates failure
  if it fails again.
- **Failed re-login policy (user decision): stay alive, stop trading, alert.**
  If re-login itself fails mid-session, the system does NOT crash (a Docker
  crash-loop would keep hitting Zerodha's login endpoint and can lock the
  account). Instead it enters a DEGRADED state: every broker call throws
  `AuthException`, a CRITICAL `AUTH_DEGRADED` (with attempt #, local timestamp,
  measured drift) goes to the flux on each retry, health goes red (D15), and
  trading resumes automatically on the first successful retry. **Exact
  schedule (tested): retries at 1m, 2m, 4m … capped at 20m apart, hard cap of
  `kite.auth.max-attempts-per-hour` (default 3) rolling per hour — never
  exceeded, so the account can never be locked by retries.**
- **The ambiguity rule (tested) — any failure after the request bytes were
  sent on a non-idempotent mutation:** place, modify, AND cancel. Timeout,
  connection reset, truncated response, or 5xx — none of them prove the order
  state didn't change. → `AmbiguousOrderStateException`. Resolution is always
  by order book: place by `tag`, modify/cancel by `order_id`. Retrying such a
  call inside the broker is forbidden. (This closes the 5xx hole the review
  found: a 500 from `/orders` never lands in plain `ApiException`.)
- **Correlation tag (user decision):** adapter auto-generates a unique tag
  (timestamp + short UUID, ≤20 alphanumeric chars) for every order when the
  caller doesn't supply one; when the strategy later supplies a
  signalId-derived tag, it takes precedence. The exception always carries the
  tag that was actually sent. The single most important safety rule in the
  module.
- **Signal-to-trade verification (user decision: alert-only on failure).**
  State machine (exhaustive — every listed transition is a test):
  ```
  SUBMITTING → SUBMITTED(order_id) → OPEN ⇄ PENDING(modify/cancel req)
      OPEN/PENDING → FILLED        (only from order-book COMPLETE + derived
                                     partials: OPEN → PARTIAL → FILLED)
      OPEN/PENDING → CANCELLED | REJECTED(+reason) | EXPIRED
      AMBIGUOUS → SUBMITTED (tag found) | LOST (grace elapsed, not found)
      any non-terminal → FAILED (Reconciler-declared LOST only)
  ```
  Rules: only order-book state transitions an order to FILLED; `PARTIAL` is
  derived from `filled_quantity` and is informational, never terminal;
  SL orders live in `OPEN` (`TRIGGER PENDING`) until triggered.
  **Exactly-once emission:** `ExecutionTracker` is synchronized with a
  CAS-style check — a given `(orderId, state)` pair emits at most one event,
  no matter how many sources (poller, reconciler, restart rebuild) observe it.
  **Poller miss never declares LOST** — only Reconciler, with the double-miss
  + grace rules (D12). Every transition publishes to the flux → Telegram.
  No resubmission, ever, in v1.
- **Restart rebuild (tested):** on startup the Reconciler pulls today's orders
  from Kite, re-adopts open ones into the tracker silently (no duplicate
  `ORDER_SUBMITTED` alerts for pre-restart orders), and emits one grouped
  `ORDER_RECOVERED` notice listing re-adopted order ids.
- **Startup positions snapshot (user decision):** after the first successful
  login, the app fetches current positions (`Broker.positions()`) and emits
  **one** `STARTUP` notification whose body lists them — symbol, side,
  qty, P&L — or an explicit "no open positions" when flat. This is the app's
  daily handshake: you know from one message that it's up, logged in, and what
  it's holding. If the positions fetch itself fails, the `STARTUP` notice
  still goes out stating positions are unavailable + the reason (and health
  reflects auth state). Exactly one STARTUP message per container start —
  rebuild notices are separate (above).
- `RateLimitException` (HTTP 429) is surfaced as-is; no sleeping inside the
  broker — backoff policy belongs to the caller, not the transport.
- Startup fails fast (Spring context does not come up) on missing/blank config
  or a failed login, so Docker's restart policy handles it and the operator
  sees the reason in logs.
- Notification delivery failures are logged-and-dropped after capped retries —
  they must never propagate into trading paths (D11).

## 8. Config

**Secrets (env vars, all mandatory):**

| Var | Meaning |
|-----|---------|
| `KITE_API_KEY` | From Kite developer portal |
| `KITE_USER_ID` | Zerodha user id |
| `KITE_PASSWORD` | Kite account password |
| `KITE_TOTP_SECRET` | Base32 TOTP seed (Kite app → account → TOTP) |
| `TELEGRAM_BOT_TOKEN` | From BotFather |
| `TELEGRAM_CHAT_ID` | Target chat/channel id |
| `LOG_LEVEL` | optional, default INFO |

**Tuning properties (optional, defaults are tested values):**

| Property | Default | Meaning |
|-----|---------|---------|
| `kite.http.connect-timeout` | 5s | HTTP connect timeout |
| `kite.http.read-timeout` | 10s | HTTP read timeout |
| `kite.http.place-read-timeout` | 15s | Read timeout for order mutations |
| `kite.auth.max-attempts-per-hour` | 3 | Hard re-login cap (account-lock guard) |
| `kite.auth.initial-retry` / `max-retry` | 1m / 20m | Re-login backoff bounds |
| `kite.orders.lost-grace` | 30s | Miss window before LOST (plus ≥2 misses) |
| `notifications.batch-window` | 1s | Normal-lane flush window |
| `notifications.batch-size` | 50 | Normal-lane flush at size |
| `notifications.buffer-size` | 1000 | Bounded buffer; overflow = drop-oldest |

Validation at startup: non-blank secrets, TOTP seed must be valid base32,
properties records immutable. **Telegram misconfig fails startup too** (a
trading system that can't alert is worse than none).

## 9. Testing strategy

**Unit / component:**
- **TOTP generator:** RFC 6238 test vectors (deterministic, time-frozen).
- **KiteHttpClient / error mapping:** `MockRestServiceServer` (Spring's own
  RestClient test support — no extra test dependency): token expiry →
  re-auth → replay once; 429 → RateLimitException; **timeout, reset, and 5xx
  on place/modify/cancel → AmbiguousOrderStateException, zero retries;
  timeout on read-only GET → NetworkException (not ambiguous).**
- **Timeout config:** assert the client factory actually carries the three
  configured values (a default slipping in = hang risk).
- **Exception contract:** malformed/empty/truncated JSON from every endpoint
  → typed `BrokerException`, never a raw exception (no Jackson/RestClient
  type escapes the adapter); every mapped exception carries operation +
  endpoint + correlation id; secrets canary extended to exception
  messages/cause chains; a scheduled task fed a failure logs one structured
  ERROR and its next run still fires.
- **KiteBroker mapping:** DTO fixtures (saved Kite JSON shapes) → domain
  records; **every row of the §6 status table is a test case; unknown string
  → UNKNOWN + CRITICAL, poller keeps running.**
- **SessionRefresher / DEGRADED:** fixed `java.time.Clock`, no sleeping —
  attempts occur exactly at 1m/2m/4m…20m, never exceeds 3/hour rolling;
  first success → AUTH_RECOVERED + health back UP.
- **Drift guard:** mocked `Date` header ±30s → WARN/SYSTEM_ALERT at the
  boundary.
- **ExecutionTracker:** legal transitions only; **exactly-once emission under
  concurrent poller+reconciler observation of the same fill; AMBIGUOUS
  resolution by tag; alert-only guarantee (no resubmission ever); PARTIAL
  derivation; SL `TRIGGER PENDING` mapped OPEN.**
- **OrderPoller/Reconciler:** order-book fixtures → expected transitions;
  **single miss → NOT LOST; double miss + grace → LOST; visibility race
  (submit before first poll sees order) → no false LOST; restart rebuild →
  silent re-adopt + one grouped ORDER_RECOVERED, no duplicate alerts.**
- **NotificationFlux:** batch flush at 1s OR 50 items; CRITICAL emits while
  normal lane is mid-batch (never behind it); overflow at 1000 → drop-oldest
  + `notifications.dropped` + WARN, publisher never blocks; sender failure ×3
  → log fallback + `notifications.send.failures`.
- **TelegramSender:** payload shape; 4096-char chunking with truncation
  marker; pacing budget respected.
- **Secrets canary:** full login flow test captures the log output and
  asserts password, TOTP seed, access token, and bot token appear nowhere.
- **Health:** DEGRADED auth and consecutive Telegram failures both flip
  `/actuator/health` to DOWN/degraded; scheduler freshness staleness flips it
  too.

**Acceptance scenarios (the "signal actually traded" definition of done):**
1. Signal path: order placed → fixture order book fills it → **exactly one**
   `ORDER_FILLED` Telegram message, correct tag/side/qty — no duplicates, no
   misses.
2. Ambiguous timeout → tag found in book → SUBMITTED → later FILLED, one
   message each; tag never found after grace → `ORDER_LOST`, signal FAILED,
   **no resubmission**.
3. Order rejected by Kite → one `ORDER_REJECTED` carrying Kite's reason text.
4. DEGRADED entered on Nth failed login (never earlier, never later),
   recovered on next success, both alerted, health follows.
5. Telegram hard-down (mocked) for 10 simulated minutes → orders still
   execute, drop/failure counters increment, health degrades, logs carry the
   fallback; once Telegram returns, pipeline resumes without restart.
6. Container restart mid-open-order → order re-adopted, no duplicate
   alerts, its eventual fill still reported exactly once.
7. Startup with missing var → context fails with an error naming that exact
   variable; with valid config → no secrets in any log line.
8. Startup positions handshake: fixture positions → **exactly one** `STARTUP`
   Telegram message listing every position (symbol/side/qty/P&L); flat
   account → explicit "no open positions"; positions endpoint down at startup
   → STARTUP still delivered, stating positions unavailable + reason.

**Not in CI:** integration against real Kite/Telegram (costs money, mutates
account/chat) — profile-gated smoke run documented instead.

## 10. Docker & runtime

- Multi-stage Dockerfile (§5-D9), compose file with `env_file: .env` and a
  healthcheck hitting `/actuator/health` — meaningful now: red when auth is
  DEGRADED, notification pipeline is failing, or poller/reconciler are stale
  (D15).
- App is a foreground Spring Boot process (keeps container alive; scheduled
  session refresh, order polling, and reconciliation run inside it).
- Metrics exposed via actuator (`/actuator/metrics` + Prometheus format if
  enabled later) — counters listed in D15.
- Timezone: session expiry, market-hours, and cron math always use
  `ZoneId.of("Asia/Kolkata")` explicitly, no dependence on container TZ.

## 11. Build

- Gradle 9 (latest, minimum 9.1.0 — first version that runs on and toolchains
  Java 25; no Gradle 10 exists yet as of 2026-10), Kotlin DSL,
  Java 25 toolchain, Spring Boot Gradle plugin. Version pinned via wrapper.
  Two subprojects: `broker` (app), `notifications` (library).
- Dependencies: `spring-boot-starter` (core/context/scheduling), `spring-boot
  -starter-web` (RestClient), `spring-boot-starter-actuator` (health/metrics),
  `reactor-core` (version managed by Boot, for the notification flux),
  `dev.samstevens:totp`, Lombok 1.18.48 (compileOnly), Jackson (via Boot),
  `spring-boot-starter-test` (tests). Nothing else without a stated need.
- **Code quality gates (wired into `check`, so `./gradlew build` fails on
  violations):**
  - **PMD** (static rules, incl. a tight category set — errorprone +
    performance + bestpractices; minimal ruleset file in `config/quality/`).
  - **SpotBugs** (bytecode bug patterns; `spotbugsMain` only, excludes file
    for known Lombok false-positives).
  - **Checkstyle** (Google style base or a slimmed ruleset; header/indent/
    naming rules — config in `config/quality/`).
  - **Spotless** (formatting — `google-java-format` or Palantir Java
    format, applied via `spotlessApply`, checked by `spotlessCheck` inside
    `check`; also formats Gradle files). One command fixes everything.
  - **SonarQube plugin** (`org.sonarqube`, latest) — wired but dormant: it
    only reports when `SONAR_HOST_URL`/`SONAR_TOKEN` are provided (env or gradle
    property); `sonar` task skipped cleanly otherwise. No server required to
    build. First run of each tool may downgrade noisy findings to warnings;
    severity tightens to fail-the-build once the baseline is clean.
- `./gradlew build` produces an executable Boot jar; Docker build uses it.

## 12. Future (out of scope, noted so the seam is right)

- Strategy module consuming `Broker` + `NotificationPublisher` + tracker
  query API (signalId → execution state).
- KiteTicker WebSocket for ticks and push order updates (replaces polling).
- Second broker or `PaperBroker` fake implementing `Broker`.
- Order reconciliation/audit persistence (DB) — today's state is in-memory,
  rebuilt from Kite on restart.
- Splitting notifications into its own deployable behind a real queue.
- Upgrade path for lost-order handling: auto-resubmit policy could replace
  alert-only once the strategy owns retry decisions.
- Metrics → dashboards/alerting (Prometheus/Grafana) beyond Telegram.

## 13. Open questions for the user

None — all decisions answered and recorded:
(1) failed re-login → DEGRADED state, exact capped backoff, never crash-loop
(§7); (2) correlation tag auto-generated by adapter, caller tag takes
precedence (§7); (3) notifications run in-process as a separate Gradle module
(§5-D6); (4) order-book poller included in v1 (§5-D12); (5) REJECTED/LOST →
alert + mark failed, never auto-resubmit (§3, §7); (6) test-engineering gap
review — all 13 findings folded in this revision (rev 2): HTTP timeouts,
5xx/mutation ambiguity rule, modify/cancel handling, exact tunables, status
mapping table, exactly-once emission, clock-drift guard, meaningful health,
metrics, Telegram limits, acceptance scenarios, full state machine + event
taxonomy, double-miss LOST rule; (7) startup positions snapshot sent as the
single STARTUP Telegram message — positions listed, or explicit flat, or
unavailable + reason (§7, acceptance scenario 8); (8) exception-handling
contract — sealed hierarchy only at the boundary, context-rich messages,
no swallowing, catch at two boundaries only, javadoc'd throws on the port
(§6, §7, §9).
