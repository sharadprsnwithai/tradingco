# Strategy Specification: Intraday Trend & Momentum Option Selling

## 1. Overview
An intraday algorithmic trading strategy designed for liquid index instruments (e.g., Nifty 50, Sensex). The strategy combines a trend-following indicator with a higher-timeframe momentum filter to execute high-probability Out-of-the-Money (OTM) short option positions (Credit Spreads with deep OTM hedge) to capture directional moves and theta decay.

---

## 2. Market & Timeframe Configurations
* **Underlying Instruments:** NIFTY, SENSEX, or liquid index derivatives.
* **Execution/Base Timeframe:** 15-Minute Candles (`15m`) — evaluated every 15 minutes.
* **Higher Timeframe (HTF) Filter:** 1-Hour Candles (`1h`) — fetched every hour from Kite.
* **Trading Style:** Intraday (Auto-exit before market close).

---

## 3. Indicator Parameters
### A. SuperTrend (Primary Trend Filter - 15m Timeframe)
* **ATR Length:** `7`
* **Multiplier / Factor:** `3.0`
* **Calculation:** Uses TA-Lib `Core.atr()` for ATR computation.
* **Signals:**
  * `Price > SuperTrend` → **Bullish State**
  * `Price < SuperTrend` → **Bearish State**

### B. Relative Strength Index - RSI (Momentum Filter - 1h Timeframe)
* **Calculation Period:** `14` periods computed on **1-Hour** timeframe.
* **Threshold / Base Line:** `50.0` (Ignore traditional 70/30 or 80/20 bands).
* **Calculation:** Uses TA-Lib `Core.rsi()` for RSI computation.
* **Data Source:** 1-hour candles fetched from Kite every hour.
* **Signals:**
  * `RSI_1H > 50` → **Bullish Momentum**
  * `RSI_1H < 50` → **Bearish Momentum**

---

## 4. Entry Conditions & Instrument Selection

### Data Flow
```
Kite Connect API
    │
    ├── 1h candles ──→ RSI-14 calculation (every hour)
    │                   └── Stored in context buffer
    │
    └── 15m candles ──→ SuperTrend calculation (every 15 min)
                        └── Entry/Exit evaluation on each 15m close
```

### Bullish Setup (Sell Put Option - PE)
* **Trigger Conditions (evaluated on 15m bar close):**
  1. `Close_15m > SuperTrend_15m` (SuperTrend is Green)
  2. `RSI_1H > 50`
  3. No open positions currently active for the same direction.
* **Execution Action:**
  * Identify OTM Put option with Delta closest to **-0.20** (|Δ| ≈ 0.20).
  * Premium constraint: Target premium ≥ ₹70. If the current weekly expiry premium is < 70, select the nearest strike from the next weekly expiry.
  * **Action:** **SELL** 1 lot (or designated quantity) of the selected PE strike.
  * *(Margin Hedge)*: **BUY** deep OTM PE (e.g., 10–20 points premium / Δ ≈ 0.05) for margin relief.

---

### Bearish Setup (Sell Call Option - CE)
* **Trigger Conditions (evaluated on 15m bar close):**
  1. `Close_15m < SuperTrend_15m` (SuperTrend is Red)
  2. `RSI_1H < 50`
  3. No open positions currently active for the same direction.
* **Execution Action:**
  * Identify OTM Call option with Delta closest to **0.20** (Δ ≈ 0.20).
  * Premium constraint: Target premium ≥ ₹70. If the current weekly expiry premium is < 70, select the nearest strike from the next weekly expiry.
  * **Action:** **SELL** 1 lot (or designated quantity) of the selected CE strike.
  * *(Margin Hedge)*: **BUY** deep OTM CE (e.g., 10–20 points premium / Δ ≈ 0.05) for margin relief.

---

## 5. Risk Management & Exit Rules

### A. Stop Loss (SL)
* Set a hard Stop Loss at **+30% of the entry premium** for the short leg:
  `SL Price = Entry Premium × 1.30`
* If the short option premium reaches or exceeds `SL Price`, immediately send a market order to close the position (and square off any associated hedge leg).

### B. Intraday Time Exit (EOD Square-Off)
* Configurable square-off time (default: **15:20 IST**).
* Close all open positions at market price regardless of P&L.

### C. Profit Target (Optional)
* Configurable profit target percentage (default: disabled).
* If premium drops by target percentage from entry, exit early to capture theta decay.

### D. Re-Entry Rule
* If a position is stopped out at +30% SL, the strategy enters a `WAIT_FOR_REENTRY` state.
* **Cooldown Period:** Wait configurable number of candles (default: 3 candles / 45 minutes) before re-entry attempt.
* If the directional condition remains valid (e.g., SuperTrend and 1H RSI are still aligned in the same direction) and the option premium drops back down to the **initial entry price**:
  * Re-enter (Sell) the same strike.
  * Apply a fresh **30% SL** based on the new entry price.

---

## 6. Position Management

### A. Lot Sizing
* Use `LotSizeService` to auto-detect lot size per underlying.
* Config parameter `lots: 1` (multiply by actual lot size).
* Example: Nifty (lot=25) × 1 lot = 25 quantity.

### B. Position Tracking
* Tag positions with `strategy-leg` metadata to distinguish short leg from hedge leg.
* Short leg receives SL monitoring; hedge leg is passive until exit.
* Both legs squared off together on exit.

### C. Directional Exclusivity
* Only 1 position per direction allowed (no opposing positions).
* If new signal fires in opposite direction, close existing position first, then re-evaluate.

---

## 7. Execution Edge Cases & Constraints
1. **Event Blackout:** Do not initiate new trades on high-impact macroeconomic/event days (e.g., Union Budget, RBI/Fed Rate Decisions, General Election results) due to IV spikes. Configurable list in application.yml.
2. **Execution Timing:** All signal checks must evaluate on bar completion (avoid intra-candle premature entries).
3. **Slippage & Delta Calculation:** Calculate Black-Scholes Delta using implied volatility (IV) from the live option chain. Use `BlackScholesPricer` with live IV from Kite quotes.

---

## 8. Configuration (application.yml)

```yaml
st-intraday:
  enabled: false
  strategy-id: ST_INTRADAY_01
  account-id: KITE_USER_01
  symbol: NFO:NIFTY_50
  super-trend:
    atr-length: 7
    multiplier: 3.0
  rsi:
    period: 14
    threshold: 50.0
  option-selection:
    target-delta: 0.20
    min-premium: 70.0
  risk:
    stop-loss-pct: 30.0
    profit-target-pct: 0.0  # 0.0 = disabled
  lots: 1
  eod-exit-time: "15:20"
  re-entry-cooldown-candles: 3
  blackout-days: []
```

---

## 9. Implementation Architecture

### Files
| File | Action | Purpose |
|------|--------|---------|
| `strategy/TechnicalIndicators.java` | MODIFIED | ATR, RSI (TA-Lib), SuperTrend |
| `marketdata/CandleAggregator.java` | MODIFIED | 1h aggregation added to HIGHER_TIMEFRAMES |
| `strategy/impl/IntradayTrendMomentumOptionSellingStrategy.java` | CREATED | Main strategy logic |
| `backtest/IntradayTrendMomentumBacktestRunner.java` | CREATED | Kite data fetch + backtest runner |
| `resources/application.yml` | MODIFIED | st-intraday config block |
| `test/.../TechnicalIndicatorsTest.java` | CREATED | Indicator unit tests |
| `test/.../IntradayTrendMomentumOptionSellingStrategyTest.java` | CREATED | Strategy unit tests |

### Dependencies
- **TA-Lib** (`com.tictactec:ta-lib:0.4.0`) for RSI and ATR calculations
- `BlackScholesPricer` for Delta calculation
- `InstrumentMasterService` for option contract lookup
- `LotSizeService` for lot size per underlying
- `MarketDataHub` for 15m and 1h candle data
- `OrderManagerService` for order execution
- `PositionManagerService` for position tracking
- `TelegramBotService` for alerts

### Live Trading Data Flow
```
Market Open
    │
    ├── Kite WebSocket ──→ Tick stream
    │       │
    │       └── CandleAggregator
    │               ├── 1m candles
    │               ├── 5m candles
    │               ├── 15m candles ──→ onCandle() → Evaluate Entry/Exit
    │               └── 60m candles ──→ onCandle() → Update RSI
    │
    └── Scheduled Tasks
            ├── Every hour: Fetch fresh 1h candles from Kite API
            └── Every 15 min: Strategy evaluation triggered by 15m candle close
```

---

## 10. Telegram Alerts

### Entry Alert
```
SELL NIFTY 24500 PE @ ₹85
SL: ₹110.5 | Hedge: 24300 PE BUY
Direction: BULLISH | RSI_1H: 58.2
```

### Exit Alert
```
SL HIT / EOD EXIT / PROFIT TARGET
NIFTY 24500 PE @ ₹112
P&L: ₹+1,250 | Duration: 2h 15m
```

### Re-entry Alert
```
RE-ENTRY: NIFTY 24500 PE @ ₹72
SL: ₹93.6 | Cooldown: 3 candles
```

---

## 11. Backtest Results

### Run: 2026-08-23
```
Period:            2026-05-27 to 2026-08-21 (3 months)
Data Source:       Kite Connect (real historical candles)
Total Candles:     1,967 (1,540 × 15m + 427 × 1h)
Total Trades:      2
Winning Trades:    1
Losing Trades:     1
Win Rate:          50.0%
Net P&L:           ₹2,509.50
Profit Factor:     1.65
Max Drawdown:      ₹3,865.50 (3.9%)
Final Capital:     ₹1,02,509.50 (from ₹1,00,000 initial)
```

### Trade Details
| Date | Direction | Entry | Exit | P&L | Reason |
|------|-----------|-------|------|-----|--------|
| 2026-06-01 | BEARISH | ₹476.11 | ₹630.73 | -₹3,865.50 | SL_HIT |
| 2026-06-17 | BULLISH | ₹476.00 | ₹221.00 | +₹6,375.00 | EOD_EXIT |

### Notes
- Low trade frequency due to range-bound market (Nifty 23,500–24,500)
- Strategy correctly avoids overtrading in choppy conditions
- First trade (BEARISH) entered during downtrend, hit SL when market reversed
- Second trade (BULLISH) captured strong uptrend, exited at EOD
