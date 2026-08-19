# Multi-Broker Trading Bot — Architecture, Implementation & Operations Plan

## 1. Objective & Core Requirements

Build a high-performance, non-blocking trading bot in Java 21 (Spring WebFlux / Project Reactor) that:

- Connects to **multiple brokers concurrently**: **Zerodha Kite** (Primary) and **Shoonya (Finvasia NorenAPI)** (Secondary).
- Enforces **strict broker isolation (Bulkhead pattern)**: an outage, socket hang, or rate-limit on Shoonya will **never** block or degrade Zerodha execution (and vice versa).
- Implements a **Strategy-to-Account 1:1 mapping model**: strategies are cleanly bound to a specific broker account.
- Implements a **Unified Event-Driven Strategy Engine**: identical execution paths across live market feeds and deterministic historical replay backtesting.
- Optimized for a **1 GB RAM VPS** (`-XX:+UseSerialGC -Xms256m -Xmx384m -XX:+ExitOnOutOfMemoryError`) with bounded memory data structures (<3 MB heap).
- Maintains a **Decoupled Market Data Hub** with automatic cross-broker feed failover (fallback to Shoonya tick stream if Kite drops after 3s silence).
- Stores **100% authentic exchange data** in local SQLite (`data/trading_state.db` and `data/instruments.db`) with zero synthetic mock data.
- Manages **Positions** with a hard separation between **Intraday (MIS/I)** and **Positional (NRML/CNC/M)** books with automated **15:14 IST EOD square-offs**.
- Provides a **3-Tier Hierarchical Kill Switch** (L1: Strategy $\rightarrow$ L2: Broker Account $\rightarrow$ L3: Global Panic) and remote operations via **Interactive Telegram Bot** + **Embedded Single-Page WebDesk (<5 MB RAM)**.

---

## 2. High-Level Architecture

```text
                                  ┌────────────────────────────────────────────────────────┐
                                  │               MarketClockScheduler (IST)               │
                                  │   08:30 Auth -> 09:05 Warmup -> 09:15 Open             │
                                  │   15:10 Lock -> 15:14 Square-Off -> 15:30 Close        │
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

## 3. Detailed Component Specifications

### 3.1 Broker Adapters & Isolation (Bulkhead Pattern)

Each broker runs behind a normalized `BrokerAdapter` interface:

- **Dedicated Schedulers**: Kite and Shoonya operations execute on separate Reactor schedulers (`Schedulers.newBoundedElastic(10, 1000, "kite-bulkhead-pool")` and `"shoonya-bulkhead-pool"`).
- **Circuit Breakers & Timeouts**: Resilience4j circuit breakers wrap REST endpoints with strict 2.5s execution timeouts.
- **Headless Auth**: Scheduled at 08:30 AM IST using programmatic TOTP generation (`googleauth`).
  - **Shoonya New OAuth Flow**:
    1. Automated headless login performs QuickAuth redirect with TOTP and derived SHA-256 app key.
    2. Exchanges code via `/GenAcsTok` with checksum `SHA-256(Client_id + secret_key + code)`.
    3. Persists session tokens in local file (`data/shoonya_session.json`) with 12-hour validity.
  - **Kite Auth Flow**: Programmatic login exchange (API Key + Request Token / TOTP $\rightarrow$ Session Access Token).
- **Disk-Backed SQLite Instrument Master (`InstrumentMasterService`)**:
  - Persists master contracts into `data/instruments.db` with indexes on tokens, symbols, strikes, expiries.
  - In-memory `ConcurrentHashMap` holds **only active/subscribed tokens** (<1 MB RAM).

---

### 3.2 Market Data Hub & Multi-Timeframe Candle Aggregator

- **Cross-Broker Failover**: Ingests WebSocket streams from Kite (Primary) and Shoonya (Standby).
- **Silence Watchdog**: If no ticks arrive for **>3 seconds** during market hours or if the primary stream fails, automatically switches to Shoonya (**Sticky Failover**).
- **Historical Seed / Warm-up (`ShoonyaHistoricalDataService`)**:
  - Uses Shoonya REST `TPSeries` API for zero-cost historical backfill.
  - Rate-limited sequentially with a **350ms delay** via `concatMap` to strictly respect Shoonya's 1 req/sec rate limit.
- **In-Memory Fixed Circular Ring Buffer (`CircularCandleBuffer`)**:
  - Bounded ring buffer (`capacity = 300` bars per timeframe) preventing unbounded heap growth (<3 MB total RAM across all active symbols).
  - Provides $O(1)$ appends, in-place updates, and zero-allocation primitive array accessors (`getClosePrices()`).
- **Real-Time Synthetic Candles (`CandleAggregator`)**:
  - Aggregates raw ticks into clock-aligned 1m base candles, deriving 3m, 5m, and 15m bars.
  - **Flat Bar Policy**: Missing tick intervals are filled with zero-volume flat bars ($O=H=L=C=\text{prevClose}, V=0$) to keep technical indicators unbroken.
  - **Dual Reactive Stream**: Emits completed `Candle` events on boundary close and relays continuous raw `Tick` events.

---

### 3.3 Strategy Engine & Decoupled Strategy SPI

- **Decoupled Strategy SPI (`Strategy` & `StrategyContext`)**:
  - Hard decoupling: Strategies have **zero broker knowledge**, zero order formatting code, and zero OMS dependencies.
  - Pluggable SPI allows adding or removing strategies dynamically without modifying core engine components.
- **Isolated In-Memory Hot Path (`StrategyEngine`)**:
  - Dedicated **Java 21 Virtual Thread executor per strategy** ensuring complete CPU isolation.
  - Multicast non-blocking `Flux<Signal>` pipeline.
- **Core Strategies**:
  - **`VandeBharatStrategy` (5-Minute F&O Breakout)**:
    - Phase 0: Pre-market baseline setup ($PDH = \text{prevClose} \times 1.01$, $PDL = \text{prevClose} \times 0.99$).
    - Phase 1: 5m Breakout detection against $PDH/PDL$ (within 2% bound).
    - Phase 2: Inside candle search ($H \le H_{\text{breakout}}, L \ge L_{\text{breakout}}, V \le V_{\text{breakout}}$, up to 6 bars limit).
    - Phase 3: High-volume breakout entry above/below inside candle bounds.
    - Phase 4: **1:2 Risk-Reward partial profit booking (50% exit)**, high-frequency trailing stop on live ticks, and 10-period EMA cross exit on 5m candle close.
    - Phase 5: 15:10 entry lock & 15:14 automated square-off.
  - **`PdhPdlBreakoutStrategy`**:
    - 5m/15m Previous Day High/Low breakout execution with 1:2 RR target and candle extreme SL.
- **Deterministic Historical Replay Engine (`BacktestEngine`)**:
  - 100% execution parity with live trading by streaming authentic historical exchange candles through the identical `Strategy` interface.
  - Simulates intra-candle price movements (Open $\rightarrow$ High/Low $\rightarrow$ Close) to test trailing stops and partial exits realistically.

---

### 3.4 Order Management System (OMS) & Pre-Trade RMS

- **Execution Safeguards**:
  - **Marketable LIMIT Orders**: Computes `LIMIT Price = LTP + 0.5%` for BUYs and `LIMIT Price = LTP - 0.5%` for SELLs, dynamically rounded to the instrument's tick size (0.05).
  - **Paper Trading Mode (`bot.paper-trading.enabled=true`)**: Fills orders locally against authentic market LTP with realistic slippage simulation without calling broker APIs.
- **Hybrid Reconciler**: Polls broker order books every **4 seconds** to reconcile open/pending orders and synchronize terminal states.
- **Pre-Trade Risk Management (`RiskManager`)**:
  1. *Max Daily Loss Limit*: Rejects entry signals if strategy loss $\ge$ ₹5,000 or global loss $\ge$ ₹15,000.
  2. *Max Open Positions Limit*: Caps concurrent active trades per strategy (max 3) and globally (max 10).
  3. *Max Single Order Qty & Value Limit*: Caps order quantity ($\le 500$) and notional value ($\le$ ₹2,00,000).
  4. *Price Deviation Check*: Rejects orders if signal price is $> 3\%$ away from real-time LTP.
  - Exit signals are always permitted to allow risk reduction.

---

### 3.5 Position Manager & Hard Book Separation

- **`IntradayBook` (MIS/I)**:
  - Tracks live MTM and unrealized P&L from tick stream.
  - **15:10 IST**: Entry lock (`INTRADAY_ENTRY_CUTOFF`).
  - **15:14 IST**: **Automated Square-Off** (`INTRADAY_SQUARE_OFF`) generates market-exit limit orders for all open intraday positions, eliminating broker penalty fees.
- **`PositionalBook` (NRML/CNC/M)**:
  - Completely separated P&L and margin tracking; **immune and protected from 15:14 auto-square-offs**.

---

### 3.6 3-Tier Hierarchical Kill Switch (`KillSwitchService`)

- **L1 (Strategy Kill)**: Pauses specific strategy, cancels its open orders, and exits its positions.
- **L2 (Broker Account Freeze)**: Freezes specific broker account, cancels all open orders for that account, and pauses all mapped strategies.
- **L3 (Global Panic)**: Immediately halts StrategyEngine, cancels all open orders across ALL brokers, liquidates all intraday books, and logs emergency events to SQLite.

---

### 3.7 Master Market Clock Automation (`MarketClockScheduler`)

All scheduled triggers execute on Indian Standard Time (`Asia/Kolkata`) with automatic weekend and NSE holiday filtering:

| Time (IST) | Scheduled Event | Automated Action |
| :--- | :--- | :--- |
| **08:30:00** | `PRE_MARKET_SCAN` | Auto-authenticate brokers & download master contract dumps. |
| **09:05:00** | *Indicator Warm-Up* | Throttles sequential historical candle fetch (350ms delay) via Shoonya `TPSeries`. |
| **09:15:00** | `MARKET_OPEN` | Activates `StrategyEngine` event loop; begins live tick processing. |
| **09:25:00** | `OI_SCAN` | **Option Chain OI Scan**: Computes $\|PE\ \Delta OI\| + \|CE\ \Delta OI\|$, picks Top 5 stocks, syncs live feeds. |
| **15:10:00** | `INTRADAY_ENTRY_CUTOFF` | Locks all new intraday entries. |
| **15:14:00** | `INTRADAY_SQUARE_OFF` | **Liquidates all open IntradayBook (MIS) positions**. PositionalBook is protected. |
| **15:30:00** | `MARKET_CLOSE` | Dispatches market close; resets daily stats; records EOD audit report. |

---

### 3.8 Operations, Telegram Bot & WebDesk Dashboard

- **Interactive Telegram Bot (`TelegramBotService`)**:
  - Uses reactive long-polling (no webhook/domain/SSL setup required).
  - Push trade alerts on entries, exits, **1:2 RR partial profit bookings**, and risk warnings.
  - Commands & inline buttons: `/status`, `/pnl`, `/strategies`, `/panic`.
- **Embedded WebDesk Dashboard (`src/main/resources/static/index.html`)**:
  - Lightweight single-page Tailwind CSS dashboard consuming **<5 MB RAM**.
  - Real-time Server-Sent Events stream (`/api/telemetry/stream`) at 1 Hz.
  - Live total/MIS/NRML MTM counters, partitioned book tables, live order book, and double-confirmation Global Panic modal.

---

## 4. Implementation Roadmap & Verification Status

### Phase 1: Core Foundation & Broker Adapters

- [x] Define canonical domain models (`Order`, `Position`, `Tick`, `Candle`, `Signal`).
- [x] Implement `BrokerAdapter` interface.
- [x] Implement `KiteBrokerAdapter` with automated TOTP auth.
- [x] Implement `ShoonyaBrokerAdapter` with NorenAPI + TOTP auth.
- [x] Set up In-JVM Bulkhead pools and Resilience4j circuit breakers.

### Phase 2: Market Data Hub & Candle Aggregator

- [x] Build `MarketDataHub` with 3s silence watchdog tick failover across Kite and Shoonya.
- [x] Implement Shoonya REST `TPSeries` historical candle backfiller with 350ms sequential throttle.
- [x] Implement bounded in-memory multi-timeframe synthetic candle aggregator (1m, 3m, 5m, 15m) with flat bar gap filling.
- [x] Containerize with multi-stage Dockerfile and Docker Compose optimized for 1 GB RAM VPS.

### Phase 3: Strategy Engine & In-Memory Hot Path

- [x] Implement decoupled `Strategy` & `StrategyContext` SPI.
- [x] Implement in-memory `StrategyEngine` with dedicated Java 21 Virtual Thread pools.
- [x] Port `VandeBharatStrategy` (5m breakout, inside candle, 1:2 RR partial exit, trailing SL, EMA 10 cross).
- [x] Port `PdhPdlBreakoutStrategy` (Range breakout with 1:2 RR target).
- [x] Validate deterministic event replay backtesting on authentic exchange historical candles.

### Phase 4: OMS, Risk Manager & Book Separation

- [x] Build `OrderManagerService` with marketable LIMIT orders (LTP $\pm$ 0.5% buffer) and tick-size rounding.
- [x] Implement 4-second hybrid push-with-polling reconciler.
- [x] Build SQLite operational store (`TradingDbService`) storing orders, positions, risk logs, and authentic candles.
- [x] Build Pre-Trade `RiskManager` with 4-point guardrails and emergency state locks.
- [x] Implement Intraday vs Positional hard book separation with 15:14 IST EOD square-off.
- [x] Implement 3-tier hierarchical kill switch (L1 Strategy, L2 Broker, L3 Global Panic).

### Phase 5: Telegram Bot, Telemetry & Operations

- [x] Implement interactive `TelegramBotService` with long-polling, push trade alerts, and inline buttons.
- [x] Build `TelemetryRouter` with REST endpoints and Server-Sent Events (`/api/telemetry/stream`).
- [x] Build embedded single-page WebDesk dashboard in static resources (<5 MB RAM).
- [x] Implement Live Paper Trading mode (`PAPER_TRADING=true`).
- [x] Build `MarketClockScheduler` with NSE holiday filter and automated 15:14 IST square-off.
- [x] Full test suite verification (40 unit and integration tests passing).
