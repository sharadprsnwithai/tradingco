# Graph Report - D:\code\trading-bot  (2026-08-21)

## Corpus Check
- 91 files · ~75,053 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 938 nodes · 2907 edges · 44 communities detected
- Extraction: 37% EXTRACTED · 63% INFERRED · 0% AMBIGUOUS · INFERRED: 1820 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Risk & Position|Risk & Position]]
- [[_COMMUNITY_Broker Adapter Interface|Broker Adapter Interface]]
- [[_COMMUNITY_Instrument & Registry|Instrument & Registry]]
- [[_COMMUNITY_VWAP Strategy & Market Data|VWAP Strategy & Market Data]]
- [[_COMMUNITY_Broker Auth & Config|Broker Auth & Config]]
- [[_COMMUNITY_Order Management|Order Management]]
- [[_COMMUNITY_LVR Backtest|LVR Backtest]]
- [[_COMMUNITY_Backtest Runner|Backtest Runner]]
- [[_COMMUNITY_VWAP Backtest & Risk|VWAP Backtest & Risk]]
- [[_COMMUNITY_Candle Processing|Candle Processing]]
- [[_COMMUNITY_NSE Client|NSE Client]]
- [[_COMMUNITY_Shoonya Resilience|Shoonya Resilience]]
- [[_COMMUNITY_Order Model Builder|Order Model Builder]]
- [[_COMMUNITY_Order Request Builder|Order Request Builder]]
- [[_COMMUNITY_Strategy Interface|Strategy Interface]]
- [[_COMMUNITY_Strategy Context|Strategy Context]]
- [[_COMMUNITY_Architecture Documentation|Architecture Documentation]]
- [[_COMMUNITY_Trading Strategy Specs|Trading Strategy Specs]]
- [[_COMMUNITY_WebFlux Endpoints|WebFlux Endpoints]]
- [[_COMMUNITY_Reactor Testing|Reactor Testing]]
- [[_COMMUNITY_Gradle Dependencies|Gradle Dependencies]]
- [[_COMMUNITY_Frontend Dashboard|Frontend Dashboard]]
- [[_COMMUNITY_Broker Health Monitoring|Broker Health Monitoring]]
- [[_COMMUNITY_Order Book UI|Order Book UI]]
- [[_COMMUNITY_Broker Interface Pattern|Broker Interface Pattern]]
- [[_COMMUNITY_Market Data Infrastructure|Market Data Infrastructure]]
- [[_COMMUNITY_Backtest Engine|Backtest Engine]]
- [[_COMMUNITY_Order Reconciler|Order Reconciler]]
- [[_COMMUNITY_Instrument Service|Instrument Service]]
- [[_COMMUNITY_Shoonya OAuth Flow|Shoonya OAuth Flow]]
- [[_COMMUNITY_Kite Auth Flow|Kite Auth Flow]]
- [[_COMMUNITY_Project README|Project README]]
- [[_COMMUNITY_Quick Start Guide|Quick Start Guide]]
- [[_COMMUNITY_Code Reference|Code Reference]]
- [[_COMMUNITY_Test Classes|Test Classes]]
- [[_COMMUNITY_Broker API Specs|Broker API Specs]]
- [[_COMMUNITY_API Rate Limits|API Rate Limits]]
- [[_COMMUNITY_Shoonya Order Limits|Shoonya Order Limits]]
- [[_COMMUNITY_Shoonya WebSocket Limits|Shoonya WebSocket Limits]]
- [[_COMMUNITY_Kite Order Limits|Kite Order Limits]]
- [[_COMMUNITY_Kite Historical Limits|Kite Historical Limits]]
- [[_COMMUNITY_Kite WebSocket Limits|Kite WebSocket Limits]]
- [[_COMMUNITY_WebDesk Dashboard|WebDesk Dashboard]]
- [[_COMMUNITY_P&L Metrics Grid|P&L Metrics Grid]]

## God Nodes (most connected - your core abstractions)
1. `of()` - 70 edges
2. `builder()` - 36 edges
3. `NiftyVwapMomentumReversalStrategy` - 32 edges
4. `ShoonyaConfig` - 27 edges
5. `LowestVolumeReversalStrategy` - 27 edges
6. `builder()` - 24 edges
7. `OrderManagerService` - 24 edges
8. `ShoonyaBrokerAdapter` - 22 edges
9. `KiteBrokerAdapter` - 21 edges
10. `builder()` - 19 edges

## Surprising Connections (you probably didn't know these)
- `Exchange-Side SL Orders (not software-monitored)` --semantically_similar_to--> `OrderManagerService (OMS) - Marketable LIMIT Orders`  [INFERRED] [semantically similar]
  Nifty_VWAP_Momentum_Reversal_Strategy.md → multi-broker-trading-bot-plan.md
- `2 Consecutive SL Hit Kill-Switch for the Day` --semantically_similar_to--> `RiskManager (4-point pre-trade RMS)`  [INFERRED] [semantically similar]
  Nifty_VWAP_Momentum_Reversal_Strategy.md → multi-broker-trading-bot-plan.md
- `Shoonya OAuth REST API Documentation (PDF)` --references--> `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)`  [AMBIGUOUS]
  oAuth REST API.pdf → multi-broker-trading-bot-plan.md
- `LVR Risk Management (1% daily, 1:2 RR)` --semantically_similar_to--> `RiskManager (4-point pre-trade RMS)`  [INFERRED] [semantically similar]
  lowest_volume_reversal.md → multi-broker-trading-bot-plan.md
- `Hard Exit at 03:00 PM` --semantically_similar_to--> `15:14 IST Automated EOD Square-Off (MIS only)`  [INFERRED] [semantically similar]
  lowest_volume_reversal.md → multi-broker-trading-bot-plan.md

## Hyperedges (group relationships)
- **Core Event-Driven Signal Pipeline (Data->Strategy->Risk->Order->Position)** — plan_market_data_hub, plan_candle_aggregator, plan_strategy_engine, plan_risk_manager, plan_oms, plan_position_manager [EXTRACTED 1.00]
- **Multi-Broker Bulkhead Isolation (adapter, scheduler, circuit breaker, rate limiter)** — plan_broker_adapter_interface, plan_bulkhead_isolation, resilience4j_config, shoonya_rate_limits, kite_rate_limits, plan_shoonya_auth, plan_kite_auth [EXTRACTED 1.00]
- **3-Tier Kill Switch Hierarchy (L1 Strategy -> L2 Broker -> L3 Global Panic)** — plan_kill_switch, plan_strategy_engine, plan_position_manager, webdesk_panic_modal, webdesk_strategy_cards [EXTRACTED 1.00]

## Communities

### Community 4 - "Risk & Position"
Cohesion: 0.04
Nodes (11): TradingBotApplication, PositionManagerService, KillSwitchService, StrategyEngine, StrategyContextImpl, TelemetryHandler, TelemetryRouter, MockStrategy (+3 more)

### Community 13 - "Broker Adapter Interface"
Cohesion: 0.14
Nodes (1): BrokerAdapter

### Community 7 - "Instrument & Registry"
Cohesion: 0.09
Nodes (6): BrokerAdapterRegistry, InstrumentMasterService, builder(), pass(), reject(), InstrumentMasterServiceTest

### Community 3 - "VWAP Strategy & Market Data"
Cohesion: 0.06
Nodes (9): MarketDataHub, MarketClockScheduler, of(), NiftyVwapMomentumReversalStrategy, TelegramBotService, BacktestEngineTest, MarketDataHubTest, MarketClockSchedulerTest (+1 more)

### Community 0 - "Broker Auth & Config"
Cohesion: 0.02
Nodes (11): KiteAuthenticator, KiteBrokerAdapter, KiteConfig, ShoonyaAuthenticator, ShoonyaBrokerAdapter, ShoonyaConfig, LotSizeService, KitePcrProvider (+3 more)

### Community 1 - "Order Management"
Cohesion: 0.1
Nodes (8): success(), failure(), builder(), builder(), builder(), OrderManagerService, OrderManagerServiceTest, RiskManagerTest

### Community 2 - "LVR Backtest"
Cohesion: 0.04
Nodes (11): BacktestEngine, BacktestContextImpl, OpenPosition, LowestVolumeReversalBacktestRunner, ShoonyaBacktestRunner, CircularCandleBuffer, LowestVolumeReversalStrategy, DailyState (+3 more)

### Community 11 - "Backtest Runner"
Cohesion: 0.18
Nodes (2): BacktestRunner, PositionManagerServiceTest

### Community 5 - "VWAP Backtest & Risk"
Cohesion: 0.07
Nodes (5): VwapBacktestRunner, TradingDbService, RiskManager, TradingDbServiceTest, NiftyVwapMomentumReversalStrategyTest

### Community 14 - "Candle Processing"
Cohesion: 0.24
Nodes (2): CandleAggregator, FormingCandle

### Community 9 - "NSE Client"
Cohesion: 0.14
Nodes (2): NseIndiaClient, SymbolState

### Community 8 - "Shoonya Resilience"
Cohesion: 0.08
Nodes (4): ShoonyaHistoricalDataService, BrokerBulkheadManager, ShoonyaHistoricalDataServiceTest, BrokerBulkheadIsolationTest

### Community 10 - "Order Model Builder"
Cohesion: 0.11
Nodes (1): builder()

### Community 12 - "Order Request Builder"
Cohesion: 0.13
Nodes (1): builder()

### Community 15 - "Strategy Interface"
Cohesion: 0.17
Nodes (1): Strategy

### Community 16 - "Strategy Context"
Cohesion: 0.22
Nodes (1): StrategyContext

### Community 19 - "Architecture Documentation"
Cohesion: 0.67
Nodes (3): Backend Agent Context & Guidelines, Multi-Broker Trading Bot Architecture Plan, High-Level Architecture (ASCII diagram)

### Community 6 - "Trading Strategy Specs"
Cohesion: 0.05
Nodes (50): Reactive Types & Operators (Mono/Flux), Reactive Anti-Patterns to Prevent, Real-Time Responsiveness Principle, Double Confirmation Safety Modals, Live Position Table (MIS/NRML tabs), Strategy Monitor (pause/resume), Lowest Volume Reversal Strategy, Volume Exhaustion on Pullback Candles (+42 more)

### Community 39 - "WebFlux Endpoints"
Cohesion: 1.0
Nodes (1): Spring WebFlux Functional Endpoints

### Community 40 - "Reactor Testing"
Cohesion: 1.0
Nodes (1): StepVerifier Testing Requirement

### Community 41 - "Gradle Dependencies"
Cohesion: 1.0
Nodes (1): Gradle Dependency Management

### Community 18 - "Frontend Dashboard"
Cohesion: 0.5
Nodes (4): Frontend Agent Context & Guidelines, Embedded WebDesk Dashboard (<5 MB RAM, SSE), TelemetryRouter REST/SSE Endpoints (8 endpoints), SSE Connection (telemetry-update events, 3s reconnect)

### Community 20 - "Broker Health Monitoring"
Cohesion: 0.67
Nodes (3): Broker Health Badges (Kite/Shoonya), 3-Second Silence Watchdog (hot-warm failover), Feed Source Badge (Primary/Standby indicator)

### Community 24 - "Order Book UI"
Cohesion: 1.0
Nodes (2): Live Order Book (Filterable), Live Order Book Table (filterable by status)

### Community 21 - "Broker Interface Pattern"
Cohesion: 0.67
Nodes (3): BrokerAdapter Interface (normalized broker contract), Bulkhead Isolation Pattern (thread pool separation), BrokerAdapter Interface (11 methods)

### Community 17 - "Market Data Infrastructure"
Cohesion: 0.29
Nodes (7): CandleAggregator (1m->3m/5m/15m synthetic candles), CircularCandleBuffer (bounded O(1) ring buffer), ShoonyaHistoricalDataService (350ms sequential throttle), Flat Bar Gap-Filling Policy (zero-volume bars), 1 GB RAM VPS Optimization (-XX:+UseSerialGC), Shoonya TPSeries 1 req/sec Hard Limit, 350ms Sequential TPSeries Throttle Policy

### Community 42 - "Backtest Engine"
Cohesion: 1.0
Nodes (1): BacktestEngine (deterministic historical replay)

### Community 43 - "Order Reconciler"
Cohesion: 1.0
Nodes (1): 4-Second Hybrid Push-with-Polling Reconciler

### Community 44 - "Instrument Service"
Cohesion: 1.0
Nodes (1): InstrumentMasterService (SQLite + ConcurrentHashMap)

### Community 25 - "Shoonya OAuth Flow"
Cohesion: 1.0
Nodes (2): Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256), Shoonya OAuth REST API Documentation (PDF)

### Community 45 - "Kite Auth Flow"
Cohesion: 1.0
Nodes (1): Kite Auth Flow (API Key + Request Token + TOTP)

### Community 46 - "Project README"
Cohesion: 1.0
Nodes (1): Multi-Broker High-Performance Trading Bot README

### Community 47 - "Quick Start Guide"
Cohesion: 1.0
Nodes (1): Quick Start & Execution Commands

### Community 48 - "Code Reference"
Cohesion: 1.0
Nodes (1): Method-by-Method Code Reference

### Community 49 - "Test Classes"
Cohesion: 1.0
Nodes (1): 41 Verified Test Classes

### Community 50 - "Broker API Specs"
Cohesion: 1.0
Nodes (1): Broker API Specifications & Rate Limits

### Community 22 - "API Rate Limits"
Cohesion: 0.67
Nodes (3): Shoonya NorenAPI Rate Limits, Zerodha Kite Connect Rate Limits, Resilience4j RateLimiter & Bulkhead Configuration

### Community 51 - "Shoonya Order Limits"
Cohesion: 1.0
Nodes (1): Shoonya Order Execution 5 req/sec Limit

### Community 52 - "Shoonya WebSocket Limits"
Cohesion: 1.0
Nodes (1): Shoonya WebSocket 100-250 Token Limit

### Community 53 - "Kite Order Limits"
Cohesion: 1.0
Nodes (1): Kite Order Placement 10 req/sec Limit

### Community 54 - "Kite Historical Limits"
Cohesion: 1.0
Nodes (1): Kite Historical Data 3 req/sec Limit

### Community 55 - "Kite WebSocket Limits"
Cohesion: 1.0
Nodes (1): Kite WebSocket 3000 Token Limit

### Community 56 - "WebDesk Dashboard"
Cohesion: 1.0
Nodes (1): Embedded WebDesk Dashboard (index.html)

### Community 57 - "P&L Metrics Grid"
Cohesion: 1.0
Nodes (1): Metrics Overview Grid (Total/Intraday/Positional P&L)

## Ambiguous Edges - Review These
- `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)` → `Shoonya OAuth REST API Documentation (PDF)`  [AMBIGUOUS]
  oAuth REST API.pdf · relation: references

## Knowledge Gaps
- **56 isolated node(s):** `Backend Agent Context & Guidelines`, `Reactive Types & Operators (Mono/Flux)`, `Spring WebFlux Functional Endpoints`, `StepVerifier Testing Requirement`, `Reactive Anti-Patterns to Prevent` (+51 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Broker Adapter Interface`** (14 nodes): `BrokerAdapter.java`, `BrokerAdapter`, `.getBrokerId()`, `.getAccountId()`, `.authenticate()`, `.isSessionValid()`, `.isEnabled()`, `.placeOrder()`, `.modifyOrder()`, `.cancelOrder()`, `.getOrderBook()`, `.getPositions()`, `.getMargins()`, `.subscribeMarketData()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Backtest Runner`** (16 nodes): `BacktestRunner.java`, `BacktestRunner`, `.executeKiteHeadlessLogin()`, `.extractRequestToken()`, `.parseQueryParam()`, `.fetchKiteHistoricalCandles()`, `.parseKiteCandles()`, `.parseKiteTimestamp()`, `.postKiteForm()`, `.generateTotpManual()`, `.base32Decode()`, `.getOrderStream()`, `.init()`, `PositionManagerServiceTest.java`, `PositionManagerServiceTest`, `.setUp()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Candle Processing`** (13 nodes): `CandleAggregator.java`, `CandleAggregator`, `.onTick()`, `.seedCandles()`, `.handleClosed1mCandle()`, `.fillFlatCandles()`, `.getOrCreateBuffer()`, `.getCandleStream()`, `.getTickStream()`, `.getSymbolLock()`, `FormingCandle`, `.FormingCandle()`, `.toClosedCandle()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `NSE Client`** (22 nodes): `.clear()`, `NseIndiaClient.java`, `NseIndiaClient`, `.NseIndiaClient()`, `.fetchGainers()`, `.fetchLosers()`, `.clearCache()`, `.fetchFromCacheOrApi()`, `.ensureSession()`, `.fetchApiData()`, `LowestVolumeReversalStrategy.java`, `.onSchedule()`, `.performStockSelection()`, `.squareOffAllPositions()`, `.resetDailyStates()`, `.destroy()`, `SymbolState`, `.SymbolState()`, `.resetSetup()`, `.resetTrade()`, `.resetDaily()`, `.setUp()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Order Model Builder`** (18 nodes): `Order.java`, `builder()`, `.accountId()`, `.brokerId()`, `.strategyId()`, `.symbol()`, `.exchange()`, `.instrumentToken()`, `.transactionType()`, `.quantity()`, `.price()`, `.triggerPrice()`, `.orderType()`, `.productType()`, `.bookType()`, `.tag()`, `.updatedAt()`, `.build()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Order Request Builder`** (15 nodes): `OrderRequest.java`, `builder()`, `.accountId()`, `.brokerId()`, `.symbol()`, `.exchange()`, `.instrumentToken()`, `.quantity()`, `.price()`, `.triggerPrice()`, `.orderType()`, `.productType()`, `.tag()`, `.strategyId()`, `.build()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Strategy Interface`** (12 nodes): `Strategy.java`, `Strategy`, `.getStrategyId()`, `.getAssignedAccountId()`, `.getSubscribedSymbols()`, `.init()`, `.onTick()`, `.onCandle()`, `.onSchedule()`, `.destroy()`, `.isEnabled()`, `.setEnabled()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Strategy Context`** (9 nodes): `StrategyContext.java`, `StrategyContext`, `.getStrategyId()`, `.getAssignedAccountId()`, `.emitSignal()`, `.getLastCandle()`, `.getHistoricalCandles()`, `.getClosePrices()`, `.now()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `WebFlux Endpoints`** (1 nodes): `Spring WebFlux Functional Endpoints`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Reactor Testing`** (1 nodes): `StepVerifier Testing Requirement`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Gradle Dependencies`** (1 nodes): `Gradle Dependency Management`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Order Book UI`** (2 nodes): `Live Order Book (Filterable)`, `Live Order Book Table (filterable by status)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Backtest Engine`** (1 nodes): `BacktestEngine (deterministic historical replay)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Order Reconciler`** (1 nodes): `4-Second Hybrid Push-with-Polling Reconciler`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Instrument Service`** (1 nodes): `InstrumentMasterService (SQLite + ConcurrentHashMap)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Shoonya OAuth Flow`** (2 nodes): `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)`, `Shoonya OAuth REST API Documentation (PDF)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Kite Auth Flow`** (1 nodes): `Kite Auth Flow (API Key + Request Token + TOTP)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Project README`** (1 nodes): `Multi-Broker High-Performance Trading Bot README`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Quick Start Guide`** (1 nodes): `Quick Start & Execution Commands`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Code Reference`** (1 nodes): `Method-by-Method Code Reference`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Test Classes`** (1 nodes): `41 Verified Test Classes`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Broker API Specs`** (1 nodes): `Broker API Specifications & Rate Limits`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Shoonya Order Limits`** (1 nodes): `Shoonya Order Execution 5 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Shoonya WebSocket Limits`** (1 nodes): `Shoonya WebSocket 100-250 Token Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Kite Order Limits`** (1 nodes): `Kite Order Placement 10 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Kite Historical Limits`** (1 nodes): `Kite Historical Data 3 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Kite WebSocket Limits`** (1 nodes): `Kite WebSocket 3000 Token Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `WebDesk Dashboard`** (1 nodes): `Embedded WebDesk Dashboard (index.html)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `P&L Metrics Grid`** (1 nodes): `Metrics Overview Grid (Total/Intraday/Positional P&L)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)` and `Shoonya OAuth REST API Documentation (PDF)`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **Why does `of()` connect `VWAP Strategy & Market Data` to `Broker Auth & Config`, `Order Management`, `LVR Backtest`, `Risk & Position`, `VWAP Backtest & Risk`, `Instrument & Registry`, `Shoonya Resilience`, `NSE Client`, `Backtest Runner`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Why does `builder()` connect `Order Model Builder` to `Order Management`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **Are the 69 inferred relationships involving `of()` (e.g. with `.getOrderBook()` and `.getPositions()`) actually correct?**
  _`of()` has 69 INFERRED edges - model-reasoned connections that need verification._
- **Are the 19 inferred relationships involving `builder()` (e.g. with `.subscribeMarketData()` and `.parseOrderBook()`) actually correct?**
  _`builder()` has 19 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Backend Agent Context & Guidelines`, `Reactive Types & Operators (Mono/Flux)`, `Spring WebFlux Functional Endpoints` to the rest of the system?**
  _56 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Risk & Position` be split into smaller, more focused modules?**
  _Cohesion score 0.04 - nodes in this community are weakly interconnected._