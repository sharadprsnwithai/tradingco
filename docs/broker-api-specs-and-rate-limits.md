# Broker API Specifications & Rate Limits

This document specifies the exact API rate limits, connection constraints, and throttling policies for **Shoonya (Finvasia NorenAPI)** and **Zerodha Kite Connect**, which must be strictly enforced by the bot's resilience layer (`BrokerBulkheadManager`).

---

## 1. Shoonya (Finvasia NorenAPI)

Shoonya enforces server-side rate limits per user session and IP address. Violations result in error messages such as `429 Too Many Requests`, `Rate Limit Exceeded`, or temporary session termination.

### 1.1 Endpoint-Specific Rate Limits

| Category | Endpoint / Operation | Safe Limit | Hard Ceiling | Recommended Enforcement in Bot |
| :--- | :--- | :--- | :--- | :--- |
| **Historical Data** | `TPSeries` / `GetTimePriceSeries` | **1 req / sec** | 2 req / sec | **Sequential throttle with $\ge$ 350ms–1000ms delay** via `concatMap` |
| **Order Execution** | `PlaceOrder`, `ModifyOrder`, `CancelOrder` | **5 req / sec** | 10 req / sec | `RateLimiter` configured at 5 calls / sec with 2s timeout |
| **Account & Reports** | `OrderBook`, `PositionBook`, `Limits` (Margins) | **1 req / sec** | 2 req / sec | Polling interval strictly set to **3–5 seconds** |
| **Instrument Master** | Text master zip (`NSE_symbols.txt.zip`, etc.) | **1 call / day** | N/A (Static CDN) | Downloaded once daily during pre-market (08:30 IST) to disk SQLite |
| **WebSocket Stream** | `NorenWSTP` (`wss://api.shoonya.com/NorenWSTP/`) | **100–250 tokens** | 250 tokens | Bounded subscription batching; reconnect backoff $\ge$ 5s |

### 1.2 Shoonya Historical Backfill (`TPSeries`) Constraints

- **Payload Limits**: Returns intraday bars in increments (e.g. 1-minute, 5-minute) up to a max history window (~1000 bars per call).
- **Concurrency Danger**: Parallel REST calls to `TPSeries` frequently trigger IP-level blocks.
- **Bot Mitigation**: All historical warm-up calls for active watchlist symbols run sequentially during pre-market (09:05 IST) through a dedicated single-threaded queue.

---

## 2. Zerodha Kite Connect

Zerodha publishes official, strict rate limits enforced per API key. Exceeding limits returns HTTP `429 Too Many Requests` or HTTP `403 Forbidden`.

### 2.1 Endpoint-Specific Rate Limits

| Category | Endpoint / Operation | Official Limit | Recommended Enforcement in Bot |
| :--- | :--- | :--- | :--- |
| **Order Placement / Modification** | `/orders/regular`, `/orders/amo` | **10 req / sec** | `RateLimiter` at 10 calls / sec with bulkhead pool |
| **Historical Data API** | `/instruments/historical/...` | **3 req / sec** | `RateLimiter` at 3 calls / sec (requires paid add-on) |
| **Quotes & Market Data** | `/quote`, `/quote/ohlc`, `/quote/ltp` | **1 req / sec** | Primary data source is WebSocket; avoid REST polling |
| **General / Portfolio** | `/orders`, `/portfolio/positions`, `/user/margins` | **3 req / sec** | Polling interval 3–5 seconds |
| **WebSocket Stream** | `wss://ws.kite.trade` | **3,000 tokens** | Single WebSocket connection handles full trading universe |

---

## 3. Resilience4j Rate Limiter & Bulkhead Configuration

The bot configures isolated rate limiters in `BrokerBulkheadManager`:

```java
// Shoonya Rate Limiter (Conservative bounds)
RateLimiterConfig shoonyaConfig = RateLimiterConfig.custom()
    .limitForPeriod(5)
    .limitRefreshPeriod(Duration.ofSeconds(1))
    .timeoutDuration(Duration.ofSeconds(2))
    .build();

// Kite Rate Limiter
RateLimiterConfig kiteConfig = RateLimiterConfig.custom()
    .limitForPeriod(3)
    .limitRefreshPeriod(Duration.ofSeconds(1))
    .timeoutDuration(Duration.ofSeconds(2))
    .build();
```

### 3.1 Historical Backfill Throttle Policy (Shoonya)

```java
// Sequential execution with 350ms inter-request delay
Flux.fromIterable(activeSymbols)
    .concatMap(symbol -> shoonyaService.fetchHistoricalCandles(symbol, "1", 200)
        .delaySubscription(Duration.ofMillis(350)))
    .subscribeOn(brokerBulkheadManager.getShoonyaScheduler());
```
