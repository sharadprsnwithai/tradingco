# Forecast Range Prediction Spec — TFT + LSTM

## Intent
Build a forecasting module that, on the **last working day of each month**, predicts a **probability range** for NIFTY 50, SENSEX, BANK NIFTY, and the top 10 NSE stocks for the **next month** (20-22 trading days), and makes that range + confidence available to the existing strategy engine.

## Goals
- **G1.** Run monthly on last trading day (~15:30 IST), output persisted per-asset.
- **G2.** Range = 80% and 95% prediction intervals in index points / price band.
- **G3.** Probability of finishing in range, median forecast, expected daily volatility.
- **G4.** Integrate into the existing Spring Boot bot (`./gradlew check` clean).
- **G5.** Offline retraining script + CI-free data ingestion.
- **G6.** Python forecast job sends Telegram message with range + probability each month.

## Non-Goals
- **NG1.** Real-time intraday prediction (daily data only).
- **NG2.** Options pricing / Greek calculations (that stays in the strategy engine).
- **NG3.** Live trading signals — this is a forecast, not a trade trigger. Strategy decides whether to use it.
- **NG4.** A web UI (CLI + persisted JSON + Telegram summary only).

## Anti-Goals
- **AG1.** No per-stock 5-minute retraining (too costly, too noisy).
- **AG2.** No external SaaS dependency you can't run offline (all components local Docker / Python scripts).

## Constraints
- **C1.** Java 25 + Spring Boot 4.1 base for the bot (keep new code consistent).
- **C2.** Python 3.12 for model training/forecasting (GluonTS / PyTorch Forecasting).
- **C3.** No new cloud services; local Docker compose for the model API.
- **C4.** Data source: NSE India CSVs (historical daily OHLCV + VIX); versioned under `data/forecast/nse_daily/`; download cadence = once per month on last trading day (offline-first, no runtime API key needed).
- **C5.** All forecasts reproducible by date (deterministic random seeds; model artifacts versioned under `model_artifacts/`).
- **C6.** Probability persistence retention = 48 months (4 years) under `data/forecast/history/`; auto-prune beyond that; monthly audit check.
- **C7.** Python forecast job sends Telegram message using existing `NotificationPublisher` service — no new dependencies.

## Architecture

```
┌─────────────┐    daily CSVs    ┌──────────┐
│ nse_downloader.py │ ─────────> │ data/    │
└──────────────┘                  │   nse_daily/
                                    └──────────┘
                                           │
                                           ▼
                            ┌──────────────────────┐
                            │ train_model.py        │ train once
                            │   LSTM + TFT          │
                            │   quantile loss       │
                            └──────────────────────┘
                                           │
                             model_artifacts/
                                           │
                                           ▼
            ┌──────────────────────────────────────────────┐
            │ forecast_service.py  (Docker)                 │
            │   /forecast/run → writes data/forecast/      │
            │   JSON + sends Telegram alert                │
            └──────────────────────────────────────────────┘
                                           │
                    REST                │
                                           ▼
            ┌──────────────────────────────────────────────┐
            │ Strategy engine (Java Spring)                 │
            │   ForecastClient → ForecastProperties         │
            │   IntradayOptionStrategy reads VIX gate       │
            │   from forecast (replaces unused props)       │
            │   Telegram via NotificationPublisher          │
            └──────────────────────────────────────────────┘
```

## Components

### 1. Data Ingestion (`scripts/forecast/download_nse.py`)
**Data ingestion (`scripts/forecast/download_nse.py`)**
- Source: `yfinance` Python library (Yahoo Finance) — pulls NIFTY 50 (`^NSEI`), SENSEX (`^BSESN`), BANK NIFTY (`^NSEBANK`), VIX (`INDIAVIX`), and top-10 NSE stocks: RELIANCE, HDFC BANK, ICICI BANK, TCS, BAJAJ FINANCE, INFOSYS, AXIS BANK, NESTLE, TITAN, ITC.
- Stores CSVs in `data/forecast/nse_daily/{symbol}.csv`.
- Re-run monthly on last trading day to pull last 22 trading days.
- Offline-first: download once, cache CSV; forecast API reads local CSV only.

### 2. Training (`scripts/forecast/train_model.py`)
- **Quantile regression** with PyTorch Forecasting `TemporalFusionTransformer`.
- LSTM baseline with Monte Carlo dropout for comparison.
- Features: `log_returns`, `volume`, `vix`, `vol_22d`, `day_of_month`, `monthly_ret`.
- Trains once; saves to `model_artifacts/tft_nse.tar.gz` and `lstm_nse.tar.gz`.

### 3. Forecast API (`forecast_api/Dockerfile` + `forecast_service.py`)
- Flask API on port 5599.
- Endpoint: `POST /forecast/run` — triggers forecast for next month, writes JSON, **sends Telegram alert**.
- Output format:
  ```json
  {
    "generated_at": "2026-10-31T15:30:00+05:30",
    "period": { "from": "2026-11-01", "to": "2026-11-30" },
    "assets": [
      {
        "symbol": "NIFTY 50",
        "median": 25050.0,
        "range80": [24200.0, 25800.0],
        "range95": [23500.0, 26500.0],
        "prob_in_range": 0.82,
        "expected_vol": 1.2,
        "model": "tft",
        "telegram_alert_sent": true
      }
    ]
  }
  ```
- **`prob_in_range`**: probability of actual price landing within the 80% range for the next month.
- **Telegram message** is sent automatically after forecast generation using the existing `NotificationPublisher` service.
- Telegram message example: `"NIFTY 50 monthly forecast: 80% range [24,200 - 25,800] | 95% range [23,500 - 26,500] | Prob: 82% | Med: ₹25,050"`

### 4. Forecast History (`data/forecast/history/`)
- Every monthly run appends a date-stamped copy: `forecast_YYYY-MM.json`.
- Retention policy: keep **48 months** (4 years) of forecasts; prune older than 1460 days automatically at run time.
- Purpose: audit trail so you can verify, after 2–4 years, whether the predicted probability matched actual results (calibration check).

### 5. Java Integration
#### 5a. `ForecastProperties` (strategy module)
```java
@ConfigurationProperties("forecast")
public record ForecastProperties(
    String apiUrl,
    Path cacheFile,   // data/forecast/latest.json
    boolean enabled   // enable/disable forecast integration
) {}
```

#### 5b. `ForecastClient` (strategy module)
```java
public interface ForecastClient {
    Optional<ForecastRange> getRange(String symbol);
    boolean refresh();   // calls API or checks cache
    boolean sendTelegramAlert();  // triggers Telegram message about forecast
}
```
- Implementation: `RestForecastClient` (calls Flask API) with `FileForecastCache` fallback.
- On `ApplicationReadyEvent`:
  1. `refresh()` to get latest forecast; if API fails, use cached JSON.
  2. `sendTelegramAlert()` to notify users via `NotificationPublisher`.

#### 5c. `ForecastRange` record
```java
public record ForecastRange(
    String symbol,
    BigDecimal median,
    BigDecimal range80Low,
    BigDecimal range80High,
    BigDecimal range95Low,
    BigDecimal range95High,
    double probInRange,
    double expectedVol,
    LocalDate generatedAt,
    LocalDate periodFrom,
    LocalDate periodTo
) {}
```

### 6. Wiring into Strategy (updated per code verification)
- `IntradayOptionStrategy.executeHedge`:
  - `ForecastClient.getRange(index.name())` provides forecast data (optional enhancement — engine already has VIX gate at `IntradayOptionStrategy:540`).
  - Existing VIX gate (`maxVixThreshold`) is **already wired** and functional (line 540); forecast adds external validation.
- `PositionRiskManager`:
  - `expectedVol` from forecast can adjust SL multiplier (optional enhancement — existing risk manager runs `@Scheduled` at line 318).
- **Risk loop verified**: `checkRisk()` already has `@Scheduled(fixedDelay = 1000)` (`IntradayOptionStrategy:318`).
- **Config fields verified**: `maxVixThreshold`, `maxSpreadPercentage`, `profitTargetPercentage` already read in engine and gateway (`LiveStrategyGateway:83` uses `maxSpreadPercentage`).
- **Tick utils verified**: `TickUtils.java` handles rounding centrally; duplication removed.
- **Order tags verified**: `LiveStrategyGateway.generateOrderTag()` uses incrementing counter (`tagCounter`) — collision risk eliminated.
- **NPE fix applied**: `PositionRiskManager:114` null guard added (`slOrder.averagePrice()` checked before `compareTo`).
- **Telegram integration**: Uses existing `NotificationPublisher` (from `StrategyAlertService`) — no new dependencies.

## Data Flow (Monthly)
1. **Last trading day, ~15:30 IST**: Flask API endpoint called (via `ForecastClient.refresh()`).
2. Forecast JSON written to `data/forecast/latest.json`.
3. Historical archive: `data/forecast/history/forecast_YYYY-MM.json` created.
4. Retained: 48 months of history; auto-prune older.
5. Strategy engine loads range at next run cycle.
6. **Calibration audit**: after first 2 years (and yearly after that) run `scripts/forecast/audit_calibration.py` to compare forecast ranges vs actual month-ends; verifies probability calibration over 4-year history.
7. **Telegram alert**: `ForecastClient.sendTelegramAlert()` sends monthly forecast summary.

## Calibration & Retention
- **Retention policy**: 48 months (4 years) under `data/forecast/history/`.
- **Calibration verification**: `scripts/forecast/audit_calibration.py` (baseline: out-of-sample 2024 data; ongoing post-deployment). Compares forecast ranges vs actual monthly close; logs whether the 80% range was hit ~80% of the time (coverage check over 4-year window).
- **Data ingestion**: `scripts/forecast/download_nse.py` — offline-friendly; no runtime API key needed.
- **Telegram integration**: Uses existing `NotificationPublisher` — Telegram API allows 30 messages/sec; our bot sends 1 message/month, well under limit.

## Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| API call fails on last day | Cache fallback; retry next day |
| Forecast overconfident | Validate on out-of-sample 2024 data; log daily error |
| Model drift (regime changes) | Monthly retrain required; flag accuracy in alerts |
| Python/Java version mismatch | Pin versions in Docker + requirements.txt |
| Probability calibration degrades over time | Ongoing calibration audit; retrain if coverage < target |
| Long-term forecast storage grows unbounded | 48-month retention; auto-prune older (1460-day cap) |
| Telegram API rate limits | Telegram API allows 30 messages/sec; our bot sends 1/month |

## Acceptance Criteria
- **AC1.** `/forecast/run` returns valid JSON for all 13 assets in < 15 min.
- **AC2.** `./gradlew :strategy:test` passes with `ForecastClient` mock test.
- **AC3.** Forecast JSON appears in `data/forecast/latest.json` when API healthy.
- **AC4.** Historical archive `data/forecast/history/forecast_YYYY-MM.json` created each run.
- **AC5.** `scripts/forecast/audit_calibration.py` validates forecast ranges (e.g., 80% ranges hit ~80% of time in backtest).
- **AC6.** `./scripts/forecast/audit_calibration.py` runs on schedule (e.g., each month after month-end) and logs calibration statistics over 2-year window.
- **AC7.** Docker compose spins up `forecast_api` independently of the bot.
- **AC8.** Retention policy auto-prunes files older than 730 days.
- **AC9.** Telegram message sent monthly with range + probability for all assets (NIFTY, SENSEX, BANK NIFTY, top-10 stocks).
- **AC10.** `sendTelegramAlert()` uses existing `NotificationPublisher` — no new dependencies.

## Build Commands
```bash
# Python (training)
make train      # scripts/forecast/train_model.py

# Docker (forecast API)
docker compose up forecast_api -d

# Java bot tests
./gradlew :strategy:test --tests "*Forecast*"

# Calibration audit (post-deployment)
make audit-calibration  # scripts/forecast/audit_calibration.py
```

## Not Covered
- Options pricing models (stays in strategy engine).
- Intraday retraining or minute-level prediction.
- UI dashboard (future phase).