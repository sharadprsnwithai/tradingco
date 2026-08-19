# Multi-Broker High-Performance Trading Bot

A production-grade, non-blocking algorithmic trading bot built with **Java 21**, **Spring WebFlux**, and **Project Reactor**. Connects concurrently to **Zerodha Kite Connect** (Primary) and **Shoonya / Finvasia NorenAPI** (Secondary) with strict Bulkhead isolation, an in-memory hot path, pre-trade RMS guardrails, automated market scheduling, an interactive Telegram bot, and an embedded single-page WebDesk dashboard (<5 MB RAM).

Optimized for deployment on a **1 GB RAM VPS** using `-XX:+UseSerialGC -Xms256m -Xmx384m -XX:+ExitOnOutOfMemoryError`.

---

## 1. High-Level Architecture

```text
                                  ┌────────────────────────────────────────────────────────┐
                                  │               MarketClockScheduler (IST)               │
                                  │   08:30 Auth -> 09:05 Warmup -> 09:15 Open             │
                                  │   09:25 OI Scan -> 15:10 Lock -> 15:14 Square-Off      │
                                  └───────────────────────────┬────────────────────────────┘
                                                              │
                                                              ▼
┌───────────────────────────────┐ ┌────────────────────────────────────────────────────────┐ ┌───────────────────────────────┐
│     Shoonya NorenAPI WS       │ │               MarketDataHub (Orchestrator)             │ │      Zerodha Kite Connect     │
│  - Secondary / Standby Feed   │─┤  - Hot-Warm Failover (3s Silence Watchdog)             ├─│  - Primary Live Tick Stream   │
│  - Rate-Limited REST TPSeries │ │  - Synthetic 1m -> 3m/5m/15m Aggregator + Flat Bars   │ │  - WebSocket Order Postbacks  │
└───────────────────────────────┘ └───────────────────────────┬────────────────────────────┘ └───────────────────────────────┘
                                                              │ Live Ticks & Closed Candles
                                                              ▼
                                  ┌────────────────────────────────────────────────────────┐
                                  │                     StrategyEngine                     │
                                  │  - Dedicated Java 21 Virtual Thread per Strategy       │
                                  │  - Vande Bharat 5m (Inside Candle, 1:2 RR, Trailing SL)│
                                  │  - PDH/PDL 5m/15m Range Breakout                       │
                                  │  - Pluggable Strategy SPI (Zero Core Modifications)    │
                                  └───────────────────────────┬────────────────────────────┘
                                                              │ Flux<Signal>
                                                              ▼
                                  ┌────────────────────────────────────────────────────────┐
                                  │                   RiskManager (RMS)                    │
                                  │  - Max Daily Loss (Strategy: ₹5k, Global: ₹15k)        │
                                  │  - Max Open Positions & Max Single Order Value         │
                                  │  - Price Deviation Guardrail (≤ 3% vs Real-Time LTP)   │
                                  └───────────────────────────┬────────────────────────────┘
                                                              │ Approved Signals
                                                              ▼
                                  ┌────────────────────────────────────────────────────────┐
                                  │               OrderManagerService (OMS)                │
                                  │  - Marketable LIMIT Orders (LTP ± 0.5% Slippage Buffer)│
                                  │  - Dynamic Tick-Size Rounding (0.05)                   │
                                  │  - Live Paper Trading Mode (PAPER_TRADING=true)        │
                                  │  - 4s Hybrid Push-with-Polling Reconciler              │
                                  └─────────────┬────────────────────────────┬─────────────┘
                                                │                            │
                                                ▼ Order Fills                ▼ Orders / Positions
                                  ┌───────────────────────────┐ ┌───────────────────────────┐
                                  │   PositionManagerService  │ │      TradingDbService     │
                                  │ ┌───────────────────────┐ │ │ (SQLite Operational Store)│
                                  │ │ IntradayBook (MIS)    │ │ │  - orders & positions     │
                                  │ │  - Live MTM & PnL     │ │ │  - authentic candles      │
                                  │ │  - 15:14 Auto Exit    │ │ │  - risk audit logs        │
                                  │ └───────────────────────┘ │ └───────────────────────────┘
                                  │ ┌───────────────────────┐ │
                                  │ │ PositionalBook (NRML) │ │
                                  │ │  - Protected from EOD │ │
                                  │ └───────────────────────┘ │
                                  └─────────────┬─────────────┘
                                                │
                                                ▼
                   ┌─────────────────────────────────────────┐ ┌─────────────────────────────────────────┐
                   │           TelegramBotService            │ │        Embedded WebDesk Dashboard       │
                   │  - Long-Polling (No Webhook/SSL needed) │ │  - Server-Sent Events (SSE) (1 Hz)      │
                   │  - Push Alerts (Entries, Exits, 1:2 RR) │ │  - Single-Page HTML/Tailwind (<5 MB RAM)│
                   │  - Interactive Inline Control Buttons   │ │  - Strategy Toggles & L3 Global Panic   │
                   └─────────────────────────────────────────┘ └─────────────────────────────────────────┘
```

---

## 2. Core Features

- **Multi-Broker Bulkhead Isolation**: Kite and Shoonya operate on dedicated `Schedulers.newBoundedElastic` thread pools with Resilience4j circuit breakers and rate limiters.
- **Master Market Clock (`Asia/Kolkata`)**:
  - `08:30 IST`: Headless TOTP session authentication & master contract sync.
  - `09:05 IST`: Sequential indicator warm-up via Shoonya `TPSeries` (350ms delay).
  - `09:15 IST`: Market Open trigger.
  - `09:25 IST`: **Option Chain OI Scan**: Computes $|PE\ \Delta OI| + |CE\ \Delta OI|$, picks Top 5 stocks, syncs live feeds.
  - `15:10 IST`: Intraday Entry Lock (no new trade entries allowed).
  - `15:14 IST`: **Automated Intraday Square-Off**: Liquidates all open `IntradayBook` (MIS) positions. `PositionalBook` (NRML/CNC) is protected.
  - `15:30 IST`: Market Close & daily stats reset.
  - `NseHolidayCalendar`: Automatically skips runs on weekends and official exchange holidays.
- **Decoupled Strategy SPI**: Strategies implement `Strategy` and consume market data via `StrategyContext`, emitting immutable `Signal` records.
- **Vande Bharat 5m Breakout Strategy**:
  - Phase 0: 09:25 IST Option Chain OI scan selecting Top 5 breakout stocks.
  - Phase 1: 5m Breakout detection against $PDH/PDL$ (within 2% boundary).
  - Phase 2: Inside candle search ($H \le H_{\text{breakout}}, L \ge L_{\text{breakout}}, V \le V_{\text{breakout}}$, up to 6 bars = 30m).
  - Phase 3: High-volume breakout entry above/below inside candle bounds.
  - Phase 4: **1:2 Risk-Reward partial profit booking (50% exit)**, high-frequency trailing stop on live ticks, and 10-period EMA cross exit on 5m candle close.
  - Phase 5: 15:10 entry lock & 15:14 automated square-off.
- **Pre-Trade RMS Guardrails**:
  1. *Max Daily Loss Limit*: Rejects entry signals if strategy loss $\ge$ ₹5,000 or global loss $\ge$ ₹15,000.
  2. *Max Open Positions*: Max 3 positions per strategy / max 10 globally.
  3. *Max Single Order Limit*: $\le 500$ qty and $\le$ ₹2,00,000 value.
  4. *Price Deviation Check*: Rejects orders if signal price is $> 3\%$ away from real-time LTP.
- **Order Management System (OMS)**:
  - Converts signals into **Marketable `LIMIT` orders** (`LTP ± 0.5%` slippage buffer) rounded to tick size (`0.05`).
  - **4-Second Hybrid Reconciler**: Polls broker order books every 4s to reconcile open orders.
  - **Live Paper Trading Mode (`PAPER_TRADING=true`)**: Fills orders locally against authentic market LTP with realistic slippage simulation without calling broker APIs.
- **3-Tier Hierarchical Kill Switch**:
  - **L1 (Strategy)**: Pauses strategy, cancels its open orders, exits its open positions.
  - **L2 (Broker Freeze)**: Freezes broker account, cancels all open orders for that account, pauses mapped strategies.
  - **L3 (Global Panic)**: Halts engine, cancels all orders across all brokers, liquidates intraday books.
- **Interactive Telegram Bot**: Reactive long-polling (`/status`, `/pnl`, `/strategies`, `/panic`) with inline control buttons and real-time push alerts.
- **Embedded WebDesk Dashboard**: Single-page HTML/Tailwind CSS dashboard served from Spring WebFlux (`http://localhost:8080`) streaming live telemetry via Server-Sent Events (`/api/telemetry/stream`).
- **Authentic SQLite Persistence**: Stores all orders, positions, risk audit logs, and authentic exchange historical candles into local SQLite (`data/trading_state.db` and `data/instruments.db`) with zero synthetic mock data.

---

## 3. Quick Start & Execution Commands

### 3.1 Prerequisites

- **Java 21 JDK** (e.g. Eclipse Temurin 21)
- **Docker & Docker Compose** (for containerized deployment)
- Active broker API credentials (Zerodha Kite and/or Shoonya NorenAPI)

### 3.2 Configuration (`.env`)

Copy `.env.example` to `.env` and fill in your credentials:

```bash
cp .env.example .env
```

```ini
SERVER_PORT=8080
PAPER_TRADING=true

# Zerodha Kite Connect
KITE_ENABLED=true
KITE_USER_ID=your_kite_user_id
KITE_PASSWORD=your_kite_password
KITE_API_KEY=your_kite_api_key
KITE_API_SECRET=your_kite_api_secret
KITE_TOTP_SECRET=your_kite_totp_secret_base32

# Shoonya (Finvasia NorenAPI)
SHOONYA_ENABLED=true
SHOONYA_USER_ID=your_shoonya_user_id
SHOONYA_ACCOUNT_ID=your_shoonya_account_id
SHOONYA_CLIENT_ID=your_shoonya_client_id
SHOONYA_SECRET_KEY=your_shoonya_secret_key
SHOONYA_PASSWORD=your_shoonya_password
SHOONYA_TOTP_SECRET=your_shoonya_totp_secret_base32

# Telegram Bot (Optional)
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id
```

### 3.3 Running Locally

```bash
# Build project
./gradlew bootJar

# Run all unit and integration tests (41 tests)
./gradlew test

# Start bot locally
java -XX:+UseSerialGC -Xms256m -Xmx384m -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata -jar build/libs/trading-bot-0.0.1-SNAPSHOT.jar
```

### 3.4 Running with Docker Compose (VPS Deployment)

```bash
# Build and start container in background
docker compose up -d --build

# View real-time logs
docker compose logs -f trading-bot

# Check container health status
docker compose ps

# Stop bot
docker compose down
```

### 3.5 Accessing WebDesk Dashboard

Open your browser at:

```text
http://<vps-ip>:8080
```

---

## 4. Method-by-Method Architecture & Code Reference

### 4.1 Broker Adapters (`com.tradingbot.adapter.*`)

#### `BrokerAdapter.java` (Interface)

| Method | Description |
| :--- | :--- |
| `String getBrokerId()` | Returns unique broker identifier (`"ZERODHA"`, `"SHOONYA"`). |
| `String getAccountId()` | Returns the user account ID associated with this adapter. |
| `Mono<Void> authenticate()` | Authenticates session with broker using automated headless TOTP exchange. |
| `Mono<Boolean> isSessionValid()` | Checks if the current cached session token is active and valid. |
| `Mono<OrderResult> placeOrder(OrderRequest request)` | Submits a new regular or trigger order to the exchange. |
| `Mono<OrderResult> modifyOrder(String orderId, OrderModifyRequest request)` | Modifies price/quantity of an open order. |
| `Mono<Void> cancelOrder(String orderId)` | Cancels an open order on the exchange. |
| `Mono<List<Order>> getOrderBook()` | Fetches the live order book from the broker API. |
| `Mono<List<Position>> getPositions()` | Fetches open and closed net positions from the broker. |
| `Mono<MarginInfo> getMargins()` | Fetches available cash and collateral margins. |
| `Flux<Tick> subscribeMarketData(List<String> symbols)` | Opens a WebSocket subscription and streams live market ticks. |

#### `KiteAuthenticator.java` & `ShoonyaAuthenticator.java`

| Method | Description |
| :--- | :--- |
| `Mono<String> authenticate()` | Executes headless authentication: generates in-memory TOTP, exchanges code for access token, and caches token to disk for 12 hours. |
| `boolean hasValidSession()` | Returns `true` if an active in-memory session token is present. |
| `String getAccessToken()` / `getSUserToken()` | Retrieves active authentication tokens for HTTP request headers. |

---

### 4.2 Market Data Hub & Aggregator (`com.tradingbot.marketdata.*`)

#### `MarketDataHub.java`

| Method | Description |
| :--- | :--- |
| `Mono<Void> subscribe(List<String> canonicalSymbols)` | Subscribes to market data for symbols on the primary broker (Kite) and starts the 3s silence watchdog. |
| `void checkSilence()` | Periodically checks if the active feed has received ticks within `silenceThreshold` (3s). |
| `void triggerFailover(String reason)` | Activates sticky failover, switching the WebSocket subscription from Kite to Shoonya. |
| `Mono<Void> switchBroker(String newBrokerId, String reason)` | Manually switches active market data feed to a specified broker. |
| `Flux<Tick> getTickStream()` | Returns the reactive multi-cast tick stream. |
| `Flux<Candle> getCandleStream(String timeframe)` | Returns the closed candle stream filtered by timeframe (`"1"`, `"3"`, `"5"`, `"15"`). |

#### `CandleAggregator.java`

| Method | Description |
| :--- | :--- |
| `void onTick(Tick tick)` | Aggregates incoming tick into forming 1m candle; rolls over on minute boundaries, fills missing gaps with flat bars, and derives higher timeframe bars (3m, 5m, 15m). |
| `void seedCandles(String symbol, String timeframe, List<Candle> candles)` | Preloads historical candles into circular buffers on startup. |
| `CircularCandleBuffer getOrCreateBuffer(String symbol, String timeframe)` | Retrieves the bounded circular buffer for a symbol-timeframe pair. |

#### `CircularCandleBuffer.java`

| Method | Description |
| :--- | :--- |
| `void add(Candle candle)` | Appends a closed candle in $O(1)$, overwriting oldest entry when capacity (`300`) is reached. |
| `void updateLast(Candle candle)` | Updates the most recent candle in-place during forming intervals. |
| `List<Candle> getCandles()` | Returns all stored candles in chronological order. |
| `double[] getClosePrices()` | Returns primitive `double[]` array of close prices for zero-allocation indicator computations. |

#### `ShoonyaHistoricalDataService.java`

| Method | Description |
| :--- | :--- |
| `Mono<List<Candle>> fetchHistoricalCandles(symbol, exch, token, tf, count)` | Calls Shoonya `TPSeries` REST API with rate-limiting protection to fetch historical bars. |
| `Flux<HistoricalWarmupResult> warmupSequentially(requests)` | Sequentially executes warm-up requests across symbols with a **350ms delay** (`concatMap`) to respect the 1 req/sec rate limit. |

---

### 4.3 Strategy Engine & Strategies (`com.tradingbot.strategy.*`)

#### `Strategy.java` (Interface)

| Method | Description |
| :--- | :--- |
| `String getStrategyId()` | Returns unique strategy instance identifier (e.g. `"VANDE_BHARAT_01"`). |
| `String getAssignedAccountId()` | Returns the broker account ID bound 1:1 to this strategy. |
| `List<String> getSubscribedSymbols()` | Returns symbols monitored and traded by this strategy. |
| `void init(StrategyContext context)` | Injects decoupled execution context and initializes strategy state. |
| `void onTick(Tick tick)` | Evaluates real-time intra-candle ticks for trailing stop and SL hits. |
| `void onCandle(Candle candle)` | Evaluates closed candles for breakouts, inside candle filters, and indicator signals. |
| `void onSchedule(ScheduledEvent event)` | Handles timed market events (`PRE_MARKET_SCAN`, `OI_SCAN`, `MARKET_OPEN`, `INTRADAY_ENTRY_CUTOFF`, `INTRADAY_SQUARE_OFF`, `MARKET_CLOSE`). |
| `void destroy()` | Cleans up state on shutdown or unregistration. |

#### `StrategyEngine.java`

| Method | Description |
| :--- | :--- |
| `void registerStrategy(Strategy strategy)` | Dynamically registers a strategy, creates a dedicated Java 21 Virtual Thread executor, and binds `StrategyContext`. |
| `void unregisterStrategy(String strategyId)` | Unregisters strategy, shuts down its virtual thread pool, and cleans up state. |
| `void syncSubscriptions()` | Synchronizes `MarketDataHub` subscriptions with the union of all active strategy symbols. |
| `void dispatchSchedule(ScheduledEvent event)` | Dispatches scheduled events in parallel virtual threads to all enabled strategies. |
| `Flux<Signal> getSignalStream()` | Returns the multicast reactive stream of all generated trading signals. |
| `void pauseStrategy(String strategyId)` / `resumeStrategy(String strategyId)` | Toggles strategy active/paused state. |

#### `VandeBharatStrategy.java`

| Method | Description |
| :--- | :--- |
| `List<String> updateWatchlistFromOiScan(List<OiScanResult> scanResults)` | Ranks F&O candidates by `\|PE Δ OI\| + \|CE Δ OI\|` and selects the **Top 5 active stocks** for the day at 09:25 IST. |
| `void evaluateEntry(StockState state, Candle candle)` | Evaluates 5m Breakout (Phase 1), searches for Inside Candle (Phase 2), and triggers entry order (Phase 3). |
| `void checkTickExits(StockState state, BigDecimal ltp)` | Checks for **1:2 Risk-Reward 50% partial profit booking** and dynamic trailing stop loss on live ticks. |
| `void checkCandleExits(StockState state, Candle candle)` | Evaluates 10-period EMA cross exit on 5m candle close. |

---

### 4.4 Order Management & Risk Management (`com.tradingbot.oms.*`, `com.tradingbot.risk.*`)

#### `OrderManagerService.java`

| Method | Description |
| :--- | :--- |
| `Mono<Order> executeSignal(Signal signal)` | Validates signal via RMS, computes Marketable `LIMIT` price (`LTP ± 0.5%`), rounds to tick size, and routes to broker adapter or paper simulator. |
| `BigDecimal calculateMarketableLimitPrice(signal, txnType, tickSize)` | Calculates buffered limit price to eliminate freak-trade execution spikes. |
| `Mono<Void> cancelOrder(String orderId)` | Cancels an open order on the exchange. |
| `Mono<Void> cancelAllOpenOrders(String accountId)` | Cancels all open/pending orders for an account or globally. |
| `Mono<Void> reconcileAllBrokers()` | Polls broker order books every 4 seconds to synchronize order statuses. |
| `void setPaperTrading(boolean enabled)` | Toggles live paper trading dry-run mode. |

#### `RiskManager.java`

| Method | Description |
| :--- | :--- |
| `Mono<RiskCheckResult> validateSignal(Signal signal)` | Runs 4-point guardrails: Max Daily Loss (Strategy ₹5k / Global ₹15k), Max Positions (3 / 10), Max Qty/Value, and Price Deviation ($\le 3\%$). Exits are always permitted. |
| `void recordRealizedLoss(String strategyId, BigDecimal loss)` | Tracks cumulative intraday realized losses against daily loss limits. |
| `void freezeBroker(String accountId)` / `unfreezeBroker(String accountId)` | Freezes/unfreezes a broker account from accepting new orders. |
| `void setGlobalPanic(boolean active)` | Sets the global panic lock state. |

#### `KillSwitchService.java`

| Method | Description |
| :--- | :--- |
| `Mono<Void> killStrategy(String strategyId, String reason)` | **Level 1**: Pauses strategy, cancels its open orders, and market-exits its positions. |
| `Mono<Void> freezeBroker(String accountId, String reason)` | **Level 2**: Freezes broker account, cancels all its open orders, and pauses mapped strategies. |
| `Mono<Void> activateGlobalPanic(String reason)` | **Level 3**: Halts StrategyEngine, cancels all open orders across ALL brokers, liquidates intraday books, and records audit logs. |
| `Mono<Void> deactivateGlobalPanic()` | Resets the Global Panic lock back to standby mode. |

---

### 4.5 Position Management & Book Separation (`com.tradingbot.position.*`)

#### `PositionManagerService.java`

| Method | Description |
| :--- | :--- |
| `void onOrderFilled(Order order)` | Updates `IntradayBook` (MIS) or `PositionalBook` (NRML/CNC) on order fill events, calculating average prices and realized P&L. |
| `void onTick(Tick tick)` | Updates live MTM and unrealized P&L for all active positions across both books. |
| `Mono<Void> executeEodIntradaySquareOff()` | **15:14 IST EOD Auto-Square-Off**: Scans `IntradayBook` and generates market-exit orders for all open intraday positions. `PositionalBook` remains untouched. |
| `Mono<Void> rehydratePositionsFromBrokers()` | Queries broker APIs at startup/mid-day reboot to populate local books with exchange truth. |

---

### 4.6 Master Market Clock & Telemetry (`com.tradingbot.scheduler.*`, `com.tradingbot.telemetry.*`, `com.tradingbot.telegram.*`)

#### `MarketClockScheduler.java`

| Method | Schedule (IST) | Description |
| :--- | :--- | :--- |
| `onPreMarketAuth()` | `08:30:00 MON-FRI` | Auto-authenticates broker sessions and synchronizes contract tokens. |
| `onPreMarketWarmup()` | `09:05:00 MON-FRI` | Warm-ups historical candle buffers via Shoonya `TPSeries` (350ms delay). |
| `onMarketOpen()` | `09:15:00 MON-FRI` | Activates StrategyEngine event loop. |
| `onPreMarketOiScan()` | `09:25:00 MON-FRI` | **Option Chain OI Scan**: Calculates `\|PE Δ OI\| + \|CE Δ OI\|`, picks Top 5 stocks, syncs live feeds. |
| `onIntradayEntryCutoff()` | `15:10:00 MON-FRI` | Locks all new trade entries. |
| `onIntradaySquareOff()` | `15:14:00 MON-FRI` | **Executes automated square-off for all open IntradayBook positions**. |
| `onMarketClose()` | `15:30:00 MON-FRI` | Dispatches market close event; resets daily risk and strategy statistics. |
| `boolean isTradingDay(LocalDate date)` | *Dynamic* | Checks if date is a weekday and not an official NSE holiday. |

#### `TelegramBotService.java`

| Method | Description |
| :--- | :--- |
| `Mono<Void> sendAlert(String message)` | Sends markdown trade alert to configured Telegram chat. |
| `void handleMessage(JsonNode messageNode)` | Parses incoming commands (`/status`, `/pnl`, `/strategies`, `/panic`, `/help`). |
| `void handleCallbackQuery(JsonNode callbackNode)` | Handles interactive button clicks (`PAUSE_STRAT:<id>`, `RESUME_STRAT:<id>`, `CONFIRM_PANIC`). |

#### `TelemetryRouter.java` & `TelemetryHandler.java`

| Endpoint | Method | Description |
| :--- | :--- | :--- |
| `/api/telemetry/summary` | `GET` | Returns JSON summary of active broker, feed status, P&L metrics, and strategies. |
| `/api/telemetry/positions` | `GET` | Returns partitioned `intraday`, `positional`, and `all` position books. |
| `/api/telemetry/orders` | `GET` | Returns open and recent order records. |
| `/api/telemetry/risk` | `GET` | Returns risk audit logs and loss metrics. |
| `/api/telemetry/stream` | `GET (SSE)` | Server-Sent Events stream emitting telemetry snapshots at **1 Hz**. |
| `/api/telemetry/kill-switch/panic` | `POST` | Triggers Level 3 Global Panic liquidation. |
| `/api/telemetry/strategy/{id}/toggle` | `POST` | Toggles pause/resume state of a strategy. |
| `/api/telemetry/paper-trading/toggle` | `POST` | Toggles live paper trading dry-run mode. |

---

## 5. Persistence & Database Schema (`data/trading_state.db`)

SQLite operational database managed by `TradingDbService.java`:

- **`orders` Table**: Stores complete order lifecycle (`id`, `broker_order_id`, `account_id`, `strategy_id`, `symbol`, `transaction_type`, `quantity`, `filled_quantity`, `price`, `status`, `tag`, `created_at`, `updated_at`).
- **`positions` Table**: Stores book positions (`id`, `account_id`, `broker_id`, `symbol`, `product_type`, `book_type`, `net_quantity`, `buy_average_price`, `sell_average_price`, `ltp`, `mtm_pnl`, `realized_pnl`, `unrealized_pnl`).
- **`historical_candles` Table**: Stores **100% authentic exchange candles** (`symbol`, `timeframe`, `timestamp_epoch`, `open`, `high`, `low`, `close`, `volume`) for indicator warm-up and backtesting replay with zero synthetic data.
- **`risk_audit_log` Table**: Stores emergency events, kill switch activations, and risk limit breach notices.
- **`instruments.db` Table**: Stores master contract lookup cache (`canonical_symbol`, `kite_token`, `shoonya_token`, `exchange`, `strike`, `expiry`).

---

## 6. Testing & Quality Verification

Run the full verification test suite:

```bash
./gradlew test
```

### Verified Test Classes (41 Tests Passing)

1. `MarketClockSchedulerTest`: 08:30 auth, 09:05 warmup, 09:15 open, 09:25 OI scan, 15:10 lock, 15:14 square-off, 15:30 close, weekend/NSE holiday filters.
2. `VandeBharatStrategyTest`: 09:25 Top 5 OI ranking, 5m breakout, inside candle filter, 1:2 RR partial exit (50%), trailing SL, 15:14 square-off.
3. `PdhPdlBreakoutStrategyTest`: Range breakout, 1:2 RR target execution, SL tracking.
4. `BacktestEngineTest`: Deterministic event replay over authentic historical candles.
5. `OrderManagerServiceTest`: Marketable LIMIT pricing, tick-size rounding (0.05), Paper Trading execution, RMS rejection handling.
6. `RiskManagerTest`: 4-point guardrails, max daily loss breach, max open positions, price deviation check ($\le 3\%$).
7. `PositionManagerServiceTest`: Intraday vs Positional book separation, live MTM updates, 15:14 EOD auto square-off.
8. `KillSwitchServiceTest`: L1 Strategy kill, L2 Broker freeze, L3 Global Panic liquidation.
9. `TelegramBotServiceTest`: Long-polling updates, callback query handling (`CONFIRM_PANIC`, `PAUSE_STRAT`), `/pnl` report formatting.
10. `TelemetryRouterTest`: REST endpoints, SSE stream, paper trading toggles.
11. `TradingDbServiceTest`: SQLite order/position persistence, real candle storage, risk audit logs.
12. `MarketDataHubTest`: Kite subscription, tick routing, 3s silence watchdog, sticky failover to Shoonya.
13. `ShoonyaHistoricalDataServiceTest`: Rate-limited sequential backfill (350ms throttle).
14. `CircularCandleBufferTest`: $O(1)$ ring buffer FIFO eviction, price array slice extraction.
15. `BrokerBulkheadIsolationTest`: Fault isolation and thread bulkhead pools across brokers.
