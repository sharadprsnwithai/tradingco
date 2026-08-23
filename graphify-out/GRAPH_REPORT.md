# Graph Report - D:\code\trading-bot  (2026-08-23)

## Corpus Check
- 120 files · ~188,719 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1335 nodes · 3994 edges · 51 communities detected
- Extraction: 41% EXTRACTED · 59% INFERRED · 0% AMBIGUOUS · INFERRED: 2367 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Core Trading System|Core Trading System]]
- [[_COMMUNITY_Broker Adapters|Broker Adapters]]
- [[_COMMUNITY_Trading Models|Trading Models]]
- [[_COMMUNITY_IronFly Adjustment|IronFly Adjustment]]
- [[_COMMUNITY_Broker Registry|Broker Registry]]
- [[_COMMUNITY_Market Data & Risk|Market Data & Risk]]
- [[_COMMUNITY_Backtest Runner|Backtest Runner]]
- [[_COMMUNITY_Agent UI|Agent UI]]
- [[_COMMUNITY_Market Infrastructure|Market Infrastructure]]
- [[_COMMUNITY_Broker Isolation Tests|Broker Isolation Tests]]
- [[_COMMUNITY_Black-Scholes Pricer|Black-Scholes Pricer]]
- [[_COMMUNITY_Strategy Engine|Strategy Engine]]
- [[_COMMUNITY_Intraday Trend Strategy|Intraday Trend Strategy]]
- [[_COMMUNITY_Database Services|Database Services]]
- [[_COMMUNITY_Technical Indicators|Technical Indicators]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]
- [[_COMMUNITY_Community 29|Community 29]]
- [[_COMMUNITY_Community 30|Community 30]]
- [[_COMMUNITY_Community 31|Community 31]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]
- [[_COMMUNITY_Community 80|Community 80]]
- [[_COMMUNITY_Community 81|Community 81]]
- [[_COMMUNITY_Community 82|Community 82]]
- [[_COMMUNITY_Community 83|Community 83]]
- [[_COMMUNITY_Community 84|Community 84]]
- [[_COMMUNITY_Community 85|Community 85]]
- [[_COMMUNITY_Community 86|Community 86]]
- [[_COMMUNITY_Community 87|Community 87]]
- [[_COMMUNITY_Community 88|Community 88]]
- [[_COMMUNITY_Community 89|Community 89]]
- [[_COMMUNITY_Community 90|Community 90]]

## God Nodes (most connected - your core abstractions)
1. `builder()` - 93 edges
2. `of()` - 87 edges
3. `NiftyVwapMomentumReversalStrategy` - 37 edges
4. `IntradayTrendMomentumOptionSellingStrategy` - 34 edges
5. `ShoonyaBrokerAdapter` - 33 edges
6. `OrderManagerService` - 31 edges
7. `KiteBrokerAdapter` - 29 edges
8. `ShoonyaConfig` - 28 edges
9. `LowestVolumeReversalStrategy` - 28 edges
10. `builder()` - 25 edges

## Surprising Connections (you probably didn't know these)
- `Exchange-Side SL Orders (not software-monitored)` --semantically_similar_to--> `OrderManagerService (OMS) - Marketable LIMIT Orders`  [INFERRED] [semantically similar]
  Nifty_VWAP_Momentum_Reversal_Strategy.md → multi-broker-trading-bot-plan.md
- `2 Consecutive SL Hit Kill-Switch for the Day` --semantically_similar_to--> `RiskManager (4-point pre-trade RMS)`  [INFERRED] [semantically similar]
  Nifty_VWAP_Momentum_Reversal_Strategy.md → multi-broker-trading-bot-plan.md
- `Shoonya OAuth REST API Documentation (PDF)` --references--> `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)`  [AMBIGUOUS]
  oAuth REST API.pdf → multi-broker-trading-bot-plan.md
- `NiftyVwapMomentumReversalStrategyTest` --semantically_similar_to--> `Iron Fly Trading Strategy`  [INFERRED] [semantically similar]
  test_output.txt → month_option_selling.md
- `DailyAnalyzer` --semantically_similar_to--> `Intraday Trend & Momentum Option Selling Strategy`  [INFERRED] [semantically similar]
  month_option_selling.md → st_intraday_option_selling.md

## Hyperedges (group relationships)
- **Core Event-Driven Signal Pipeline (Data->Strategy->Risk->Order->Position)** — plan_market_data_hub, plan_candle_aggregator, plan_strategy_engine, plan_risk_manager, plan_oms, plan_position_manager [EXTRACTED 1.00]
- **Multi-Broker Bulkhead Isolation (adapter, scheduler, circuit breaker, rate limiter)** — plan_broker_adapter_interface, plan_bulkhead_isolation, resilience4j_config, shoonya_rate_limits, kite_rate_limits, plan_shoonya_auth, plan_kite_auth [EXTRACTED 1.00]
- **3-Tier Kill Switch Hierarchy (L1 Strategy -> L2 Broker -> L3 Global Panic)** — plan_kill_switch, plan_strategy_engine, plan_position_manager, webdesk_panic_modal, webdesk_strategy_cards [EXTRACTED 1.00]
- **Option Trading Strategies in Trading Bot** — month_option_selling_iron_fly, st_intraday_option_selling_strategy, test_output_nifty_vwap_strategy_test [INFERRED 0.80]
- **Risk Management Components** — month_option_selling_daily_analyzer, month_option_selling_adjustment_handler, st_intraday_option_selling_strategy [INFERRED 0.85]
- **Market Data and Execution Services** — month_option_selling_market_data_provider, st_intraday_market_data_hub, st_intraday_order_manager_service, st_intraday_position_manager_service [EXTRACTED 1.00]

## Communities

### Community 0 - "Core Trading System"
Cohesion: 0.03
Nodes (20): getAdjustmentCount(), getAtmStrike(), getCurrentNetCredit(), getLowerBreakeven(), getTotalMtm(), getUpperBreakeven(), nullSafe(), IronFlyService (+12 more)

### Community 1 - "Broker Adapters"
Cohesion: 0.02
Nodes (11): IntradayTrendMomentumOptionSellingStrategyTest, MockStrategyContext, KiteAuthenticator, KiteBrokerAdapter, KiteBrokerAdapterTest, KiteConfig, KitePcrProvider, ShoonyaAuthenticator (+3 more)

### Community 2 - "Trading Models"
Cohesion: 0.09
Nodes (9): OrderManagerService, OrderManagerServiceTest, failure(), success(), builder(), PositionManagerServiceTest, RiskManagerTest, builder() (+1 more)

### Community 3 - "IronFly Adjustment"
Cohesion: 0.03
Nodes (14): AdjustmentHandler, BacktestContextImpl, BacktestEngine, OpenPosition, BacktestEngineTest, CandleAggregator, FormingCandle, CandleAggregatorTest (+6 more)

### Community 4 - "Broker Registry"
Cohesion: 0.05
Nodes (9): BrokerAdapterRegistry, GenericOptionChainProvider, builder(), InstrumentMasterService, InstrumentSyncService, KiteOptionChainProvider, pass(), reject() (+1 more)

### Community 5 - "Market Data & Risk"
Cohesion: 0.07
Nodes (4): NiftyVwapMomentumReversalStrategyTest, RiskManager, TradingDbService, VwapBacktestRunner

### Community 6 - "Backtest Runner"
Cohesion: 0.08
Nodes (3): BacktestRunner, IntradayTrendMomentumBacktestRunner, ShoonyaBacktestRunner

### Community 7 - "Agent UI"
Cohesion: 0.05
Nodes (50): Reactive Anti-Patterns to Prevent, Reactive Types & Operators (Mono/Flux), Live Position Table (MIS/NRML tabs), Real-Time Responsiveness Principle, Double Confirmation Safety Modals, Strategy Monitor (pause/resume), Lowest Volume Reversal Strategy, Hard Exit at 03:00 PM (+42 more)

### Community 8 - "Market Infrastructure"
Cohesion: 0.06
Nodes (4): LotSizeService, LowestVolumeReversalStrategy, SymbolState, NseIndiaClient

### Community 9 - "Broker Isolation Tests"
Cohesion: 0.06
Nodes (5): BrokerBulkheadIsolationTest, BrokerBulkheadManager, PositionManagerService, ShoonyaHistoricalDataService, ShoonyaHistoricalDataServiceTest

### Community 10 - "Black-Scholes Pricer"
Cohesion: 0.11
Nodes (2): BlackScholesPricer, IronFlyBacktestRunner

### Community 11 - "Strategy Engine"
Cohesion: 0.09
Nodes (4): MockStrategy, StrategyContextImpl, StrategyEngine, StrategyEngineTest

### Community 12 - "Intraday Trend Strategy"
Cohesion: 0.08
Nodes (2): DailyState, IntradayTrendMomentumOptionSellingStrategy

### Community 13 - "Database Services"
Cohesion: 0.09
Nodes (4): InstrumentMasterServiceTest, IronFlyDbService, TradingBotApplication, TradingDbServiceTest

### Community 14 - "Technical Indicators"
Cohesion: 0.12
Nodes (2): TechnicalIndicators, TechnicalIndicatorsTest

### Community 15 - "Community 15"
Cohesion: 0.11
Nodes (1): builder()

### Community 16 - "Community 16"
Cohesion: 0.11
Nodes (19): AdjustmentHandler, DailyAnalyzer, Iron Fly Trading Strategy, IronFlyPosition Data Model, MarketDataProvider Interface, OptionLeg Data Model, OptionType Enum (CALL, PUT), BlackScholesPricer for Delta Calculation (+11 more)

### Community 17 - "Community 17"
Cohesion: 0.12
Nodes (1): builder()

### Community 18 - "Community 18"
Cohesion: 0.13
Nodes (1): BrokerAdapter

### Community 19 - "Community 19"
Cohesion: 0.29
Nodes (9): adjust(), DailyAnalyzer, exit(), noAction(), getDecayPercentage(), getLossPercentage(), getMtmPnl(), getProfitPercentage() (+1 more)

### Community 20 - "Community 20"
Cohesion: 0.15
Nodes (1): Strategy

### Community 21 - "Community 21"
Cohesion: 0.18
Nodes (1): StrategyContext

### Community 22 - "Community 22"
Cohesion: 0.29
Nodes (7): 1 GB RAM VPS Optimization (-XX:+UseSerialGC), CandleAggregator (1m->3m/5m/15m synthetic candles), CircularCandleBuffer (bounded O(1) ring buffer), Flat Bar Gap-Filling Policy (zero-volume bars), ShoonyaHistoricalDataService (350ms sequential throttle), Shoonya TPSeries 1 req/sec Hard Limit, 350ms Sequential TPSeries Throttle Policy

### Community 23 - "Community 23"
Cohesion: 0.5
Nodes (1): TelemetryRouter

### Community 24 - "Community 24"
Cohesion: 0.5
Nodes (4): Frontend Agent Context & Guidelines, Embedded WebDesk Dashboard (<5 MB RAM, SSE), TelemetryRouter REST/SSE Endpoints (8 endpoints), SSE Connection (telemetry-update events, 3s reconnect)

### Community 25 - "Community 25"
Cohesion: 0.67
Nodes (1): of()

### Community 26 - "Community 26"
Cohesion: 0.67
Nodes (3): Backend Agent Context & Guidelines, Multi-Broker Trading Bot Architecture Plan, High-Level Architecture (ASCII diagram)

### Community 27 - "Community 27"
Cohesion: 0.67
Nodes (3): BrokerAdapter Interface (normalized broker contract), Bulkhead Isolation Pattern (thread pool separation), BrokerAdapter Interface (11 methods)

### Community 28 - "Community 28"
Cohesion: 0.67
Nodes (3): Broker Health Badges (Kite/Shoonya), 3-Second Silence Watchdog (hot-warm failover), Feed Source Badge (Primary/Standby indicator)

### Community 29 - "Community 29"
Cohesion: 0.67
Nodes (3): Zerodha Kite Connect Rate Limits, Resilience4j RateLimiter & Bulkhead Configuration, Shoonya NorenAPI Rate Limits

### Community 30 - "Community 30"
Cohesion: 1.0
Nodes (2): Live Order Book (Filterable), Live Order Book Table (filterable by status)

### Community 31 - "Community 31"
Cohesion: 1.0
Nodes (2): Shoonya OAuth REST API Documentation (PDF), Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)

### Community 72 - "Community 72"
Cohesion: 1.0
Nodes (1): Spring WebFlux Functional Endpoints

### Community 73 - "Community 73"
Cohesion: 1.0
Nodes (1): StepVerifier Testing Requirement

### Community 74 - "Community 74"
Cohesion: 1.0
Nodes (1): Gradle Dependency Management

### Community 75 - "Community 75"
Cohesion: 1.0
Nodes (1): BacktestEngine (deterministic historical replay)

### Community 76 - "Community 76"
Cohesion: 1.0
Nodes (1): 4-Second Hybrid Push-with-Polling Reconciler

### Community 77 - "Community 77"
Cohesion: 1.0
Nodes (1): InstrumentMasterService (SQLite + ConcurrentHashMap)

### Community 78 - "Community 78"
Cohesion: 1.0
Nodes (1): Kite Auth Flow (API Key + Request Token + TOTP)

### Community 79 - "Community 79"
Cohesion: 1.0
Nodes (1): Multi-Broker High-Performance Trading Bot README

### Community 80 - "Community 80"
Cohesion: 1.0
Nodes (1): Quick Start & Execution Commands

### Community 81 - "Community 81"
Cohesion: 1.0
Nodes (1): Method-by-Method Code Reference

### Community 82 - "Community 82"
Cohesion: 1.0
Nodes (1): 41 Verified Test Classes

### Community 83 - "Community 83"
Cohesion: 1.0
Nodes (1): Broker API Specifications & Rate Limits

### Community 84 - "Community 84"
Cohesion: 1.0
Nodes (1): Shoonya Order Execution 5 req/sec Limit

### Community 85 - "Community 85"
Cohesion: 1.0
Nodes (1): Shoonya WebSocket 100-250 Token Limit

### Community 86 - "Community 86"
Cohesion: 1.0
Nodes (1): Kite Order Placement 10 req/sec Limit

### Community 87 - "Community 87"
Cohesion: 1.0
Nodes (1): Kite Historical Data 3 req/sec Limit

### Community 88 - "Community 88"
Cohesion: 1.0
Nodes (1): Kite WebSocket 3000 Token Limit

### Community 89 - "Community 89"
Cohesion: 1.0
Nodes (1): Embedded WebDesk Dashboard (index.html)

### Community 90 - "Community 90"
Cohesion: 1.0
Nodes (1): Metrics Overview Grid (Total/Intraday/Positional P&L)

## Ambiguous Edges - Review These
- `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)` → `Shoonya OAuth REST API Documentation (PDF)`  [AMBIGUOUS]
  oAuth REST API.pdf · relation: references

## Knowledge Gaps
- **71 isolated node(s):** `Backend Agent Context & Guidelines`, `Reactive Types & Operators (Mono/Flux)`, `Spring WebFlux Functional Endpoints`, `StepVerifier Testing Requirement`, `Reactive Anti-Patterns to Prevent` (+66 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Black-Scholes Pricer`** (37 nodes): `BlackScholesPricer`, `.BlackScholesPricer()`, `.callDelta()`, `.callPrice()`, `.d1()`, `.d2()`, `.normalCdf()`, `.putDelta()`, `.putFromCall()`, `.putPrice()`, `BlackScholesPricer.java`, `IronFlyBacktestRunner.java`, `IronFlyBacktestRunner`, `.base32Decode()`, `.daysBetween()`, `.executeKiteHeadlessLogin()`, `.extractRequestToken()`, `.fetchKiteHistoricalCandles()`, `.findCandleOnDate()`, `.findDeltaStrike()`, `.generateTotpManual()`, `.getAtmRoundTo()`, `.getCandlesBetween()`, `.getLotSize()`, `.getMonthlyExpiries()`, `.loadEnv()`, `.main()`, `.parseKiteCandles()`, `.parseKiteTimestamp()`, `.parseQueryParam()`, `.postKiteForm()`, `.printResult()`, `.runAllUnderlyings()`, `.runIronFlyBacktest()`, `.searchKiteInstrument()`, `BlackScholesPricer.java`, `IronFlyBacktestRunner.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Intraday Trend Strategy`** (32 nodes): `IntradayTrendMomentumOptionSellingStrategy.java`, `DailyState`, `.exitTrade()`, `.reset()`, `IntradayTrendMomentumOptionSellingStrategy`, `.calculateApproxDelta()`, `.checkCandleExits()`, `.checkTickExits()`, `.destroy()`, `.getActiveShortSymbol()`, `.getAssignedAccountId()`, `.getEntriesToday()`, `.getEntryPremium()`, `.getHighPrices()`, `.getLowPrices()`, `.getPosition()`, `.getSlPrice()`, `.getStrategyId()`, `.getSubscribedSymbols()`, `.getTradeDirection()`, `.init()`, `.IntradayTrendMomentumOptionSellingStrategy()`, `.isEnabled()`, `.isLiveMode()`, `.onCandle()`, `.onSchedule()`, `.onTick()`, `.setEnabled()`, `.squareOffAll()`, `.testGetTradeDirectionDefault()`, `.getHistoricalCandles()`, `IntradayTrendMomentumOptionSellingStrategy.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Technical Indicators`** (24 nodes): `TechnicalIndicators.java`, `TechnicalIndicatorsTest.java`, `TechnicalIndicators.java`, `TechnicalIndicatorsTest.java`, `TechnicalIndicators`, `.calculateAtr()`, `.calculateEma()`, `.calculateRsi()`, `.calculateSma()`, `.calculateSuperTrend()`, `.TechnicalIndicators()`, `TechnicalIndicatorsTest`, `.testCalculateAtr_basicCase()`, `.testCalculateAtr_constantRange()`, `.testCalculateAtr_insufficientData()`, `.testCalculateAtr_nullInput()`, `.testCalculateRsi_downtrend()`, `.testCalculateRsi_insufficientData()`, `.testCalculateRsi_neutral()`, `.testCalculateRsi_uptrend()`, `.testCalculateSuperTrend_downtrend()`, `.testCalculateSuperTrend_insufficientData()`, `.testCalculateSuperTrend_uptrend()`, `.testRound()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 15`** (19 nodes): `Order.java`, `builder()`, `.accountId()`, `.bookType()`, `.brokerId()`, `.build()`, `.exchange()`, `.instrumentToken()`, `.orderType()`, `.price()`, `.productType()`, `.quantity()`, `.strategyId()`, `.symbol()`, `.tag()`, `.transactionType()`, `.triggerPrice()`, `.updatedAt()`, `Order.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 17`** (16 nodes): `OrderRequest.java`, `builder()`, `.accountId()`, `.brokerId()`, `.build()`, `.exchange()`, `.instrumentToken()`, `.orderType()`, `.price()`, `.productType()`, `.quantity()`, `.strategyId()`, `.symbol()`, `.tag()`, `.triggerPrice()`, `OrderRequest.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 18`** (15 nodes): `BrokerAdapter`, `.authenticate()`, `.cancelOrder()`, `.getAccountId()`, `.getBrokerId()`, `.getMargins()`, `.getOrderBook()`, `.getPositions()`, `.isEnabled()`, `.isSessionValid()`, `.modifyOrder()`, `.placeOrder()`, `.subscribeMarketData()`, `BrokerAdapter.java`, `BrokerAdapter.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 20`** (13 nodes): `Strategy.java`, `Strategy.java`, `Strategy`, `.destroy()`, `.getAssignedAccountId()`, `.getStrategyId()`, `.getSubscribedSymbols()`, `.init()`, `.isEnabled()`, `.onCandle()`, `.onSchedule()`, `.onTick()`, `.setEnabled()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 21`** (11 nodes): `StrategyContext.java`, `StrategyContext.java`, `StrategyContext`, `.emitSignal()`, `.getAssignedAccountId()`, `.getClosePrices()`, `.getHistoricalCandles()`, `.getLastCandle()`, `.getStrategyId()`, `.now()`, `.requestSubscriptionSync()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 23`** (4 nodes): `TelemetryRouter.java`, `TelemetryRouter.java`, `TelemetryRouter`, `.telemetryRoutes()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 25`** (3 nodes): `MarginInfo.java`, `of()`, `MarginInfo.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 30`** (2 nodes): `Live Order Book (Filterable)`, `Live Order Book Table (filterable by status)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 31`** (2 nodes): `Shoonya OAuth REST API Documentation (PDF)`, `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 72`** (1 nodes): `Spring WebFlux Functional Endpoints`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 73`** (1 nodes): `StepVerifier Testing Requirement`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 74`** (1 nodes): `Gradle Dependency Management`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 75`** (1 nodes): `BacktestEngine (deterministic historical replay)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 76`** (1 nodes): `4-Second Hybrid Push-with-Polling Reconciler`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 77`** (1 nodes): `InstrumentMasterService (SQLite + ConcurrentHashMap)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 78`** (1 nodes): `Kite Auth Flow (API Key + Request Token + TOTP)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 79`** (1 nodes): `Multi-Broker High-Performance Trading Bot README`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 80`** (1 nodes): `Quick Start & Execution Commands`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 81`** (1 nodes): `Method-by-Method Code Reference`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 82`** (1 nodes): `41 Verified Test Classes`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 83`** (1 nodes): `Broker API Specifications & Rate Limits`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 84`** (1 nodes): `Shoonya Order Execution 5 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 85`** (1 nodes): `Shoonya WebSocket 100-250 Token Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 86`** (1 nodes): `Kite Order Placement 10 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 87`** (1 nodes): `Kite Historical Data 3 req/sec Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 88`** (1 nodes): `Kite WebSocket 3000 Token Limit`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 89`** (1 nodes): `Embedded WebDesk Dashboard (index.html)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 90`** (1 nodes): `Metrics Overview Grid (Total/Intraday/Positional P&L)`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Shoonya OAuth Flow (QuickAuth + TOTP + SHA-256)` and `Shoonya OAuth REST API Documentation (PDF)`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **Why does `of()` connect `Core Trading System` to `Broker Adapters`, `Trading Models`, `IronFly Adjustment`, `Broker Registry`, `Market Data & Risk`, `Backtest Runner`, `Market Infrastructure`, `Broker Isolation Tests`, `Black-Scholes Pricer`, `Strategy Engine`, `Intraday Trend Strategy`?**
  _High betweenness centrality (0.088) - this node is a cross-community bridge._
- **Why does `builder()` connect `Trading Models` to `Core Trading System`, `Broker Adapters`, `IronFly Adjustment`, `Broker Registry`, `Backtest Runner`?**
  _High betweenness centrality (0.070) - this node is a cross-community bridge._
- **Why does `builder()` connect `Community 17` to `Trading Models`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **Are the 75 inferred relationships involving `builder()` (e.g. with `.mapSdkTick()` and `.parseOrderBook()`) actually correct?**
  _`builder()` has 75 INFERRED edges - model-reasoned connections that need verification._
- **Are the 85 inferred relationships involving `of()` (e.g. with `.getMargins()` and `.executeHeadlessLogin()`) actually correct?**
  _`of()` has 85 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Backend Agent Context & Guidelines`, `Reactive Types & Operators (Mono/Flux)`, `Spring WebFlux Functional Endpoints` to the rest of the system?**
  _71 weakly-connected nodes found - possible documentation gaps or missing edges._