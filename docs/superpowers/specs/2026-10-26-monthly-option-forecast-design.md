# Monthly Option-Selling Forecast Design — RELIANCE / TCS / HDFCBANK

## Intent

On the 1st of each month, the user manually sells options on RELIANCE, TCS, HDFCBANK
for that month's expiry — these are the **core trading assets**. The system must, on
that morning, tell him per asset (for **all 13 symbols in the existing
`download_nse.py` list**: NIFTY 50, SENSEX, BANKNIFTY, and the 10 stocks — the 3
trading assets carry the grain of the design, the rest ride along for free since the
pipeline is per-asset):

1. **Direction** — probability of up / down / flat over the expiry period.
2. **Range** — the price band the stock is likely to stay within until expiry.
3. **Volatility** — whether the month is expected to be calm or choppy.

His decision rule (his words):

- Prediction ≈ 50/50 (neutral): sell both ATM/OTM PE and CE at ~15 delta, **naked**.
- Prediction clearly bullish: sell a **call bull spread** (hedged).
- (Inferred mirror: clearly bearish → put bull spread.)

The prediction therefore drives **structure choice** (naked vs spread) and **strike
choice**, per stock. Trades stay manual; the system only outputs a decision digest.

This supersedes the model-choice part of `2026-10-25-forecast-range-prediction-design.md`
for these 3 assets; the JSON contract, Telegram delivery, history retention, and
calibration audit from that spec are reused as-is (13-asset scope narrows to 3).

## Goals

- **G1.** Run monthly on the last trading day of the prior month (~15:30 IST); produce
  per-stock verdict by the 1st trading morning.
- **G2.** Per stock output, BOTH band definitions computed from the same ~30 sample
  paths (no extra model cost — two different reductions):
  - **Terminal band** — 80%/95% quantiles of the expiry-day close (for spread legs
    and "where will it end" questions).
  - **Max-touch envelope** — 80%/95% quantiles of the path maximum/minimum
    (strike gets *touched* at any point in the month). The NAKED-OK strike zones
    MUST come from the envelope, not terminal bands.
  Plus: median, P(up), P(down), P(flat), P(volatility expansion), and both sets of
  levels formatted as candidate OTM strike zones.
- **G3.** Verdict mapping (rules below) → structure recommendation:
  BULLISH / BEARISH / NEUTRAL, plus NAKED-OK or SPREAD-ONLY flag.
- **G4.** Real predictions, not stubs — replace the hardcoded ±3% output of
  `forecast_service.py` and delete the fake artifact of `train_model.py`.
- **G5.** Backtest calibration on ≥4 years of daily history BEFORE any NAKED-OK flag
  can appear in a live message; until calibration passes, the digest says SPREAD-ONLY.
- **G6.** Telegram digest message on the 1st morning via the existing
  `NotificationPublisher` contract designed in the prior spec.
- **G7.** Persist each monthly digest to `data/forecast/history/` (48-month retention)
  and archive last-working-day close for audit (`audit_calibration.py`).

## Non-Goals

- **NG1.** No automated order placement for these monthly trades (user places manually).
- **NG2.** No intraday or minute-level predictions; daily closes only.
- **NG3.** Assets beyond the 13 in the existing symbol list (adding more is one dict
  entry in `download_nse.py` — allowed if he asks, nothing new to build).
- **NG4.** No options-chain/IV integration in this pass (candidate for phase 2: the chain's
  own implied distribution − Breeden-Litzenberger − cross-checks whether the strikes
  being sold are rich or cheap). The digest format should leave room for it.
- **NG5.** No web UI.

## Anti-Goals

- **AG1.** No new training pipeline; zero-shot pretrained model only.
- **AG2.** No uncalibrated naked-selling advice: a probability in the message must be
  backed by measured historical coverage, not a sample count alone.

## Constraints

- **C1.** Reuse `scripts/forecast/download_nse.py` data path (`data/forecast/nse_daily/*.csv`,
  yfinance, 5y daily) — RELIANCE, TCS, HDFCBANK already in its symbol list.
- **C2.** Model: Chronos-family pretrained model from HuggingFace (`chronos-forecasting`),
  zero-shot, ~30 directional sample paths per asset (Monte Carlo, seeded → deterministic
  per date, constraint C5 of prior spec). Latest Chronos-Bolt variant acceptable; exact
  version left to the builder to pick from HF with the pin recorded in requirements.
- **C3.** Python 3.12; single lightweight service — keep the existing Flask
  `forecast_service.py` shape if possible (Docker optional; Cron/manual trigger acceptable
  since no consumer besides the user's Telegram).
- **C4.** Digest message format (one block per asset, 13 assets; the 3 trading assets
  first with strike zones, indexes and the rest in compact verdict-only form), e.g.:
  ```
  RELIANCE: NEUTRAL (P(up)=47% P(down)=41% P(flat)=12%) | Vol↑ 30%
    Sell BOTH ~15d: CE zone ≥ 3050, PE zone ≤ 2890 | NAKED-OK (cal 95-band 94.8%)
  TCS: BULLISH (P(up)=71%) | SPREAD-ONLY → call bull spread near 95-band top
  ```
- **C5.** Verdict mapping table (builder may tune thresholds, defaults here):
  | P(up) | P(down) | Verdict | Structure |
  |-------|---------|---------|-----------|
  | >60% | | BULLISH | call bull spread (long leg near spot/80d, short at ≥95-band or 0.15d of IV) |
  | | >60% | BEARISH | put bull spread mirrored |
  | 40–60% (both) | | NEUTRAL | both sides ~15d; NAKED-OK only if G5 calibration passed |
  | P(vol↑) > 50% | | append: chip-win risk high → prefer spreads or widen strikes |

## Components

1. **`scripts/forecast/predict_monthly.py`** (replaces inference in
   `forecast_service.py`): load 3 CSVs → Chronos inference (~30 samples) → per-stock
   stats + verdict → `data/forecast/latest.json` (same contract keys as prior spec) +
   history file + Telegram message.
2. **`scripts/forecast/calibrate.py`**: walk-forward over ≥4y daily closes — for each
   past month-end, run the same predict step on trailing data, log 80/95-band hit rates,
   direction accuracy vs expiry close, and per-month-overlay execution simulation is NOT
   required. Emits `data/forecast/calibration.json` with measured coverages.
   Pooled across all 13 assets (~484 monthly observations) for the global NAKED-OK gate;
   per-asset rates also logged, shown in the digest for information only.
3. **NAKED-OK gate**: digest reads `calibration.json`; naked advice appears only when
   measured 95-band coverage ≥ 93% and direction accuracy ≥ 55% on the walk-forward.
   Hardcode thresholds in one place.
4. **Delete** `scripts/forecast/train_model.py` stub and `model_artifacts/` usage.

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Chronos "87% up" style numbers are sample counts, not validated accuracy | G5 calibration gate before naked advice |
| Neutral verdict with fat-tail month (crash) breaks naked strangle | P(vol↑) appendix rule + SPREAD-ONLY flag; user sees band + prob, decides |
| Stock-specific expiry dates shift (exchange calendar) | Forecast period = 1st trading day → last trading day of month; user maps to expiry himself |
| Zero-shot model biased by foreign regimes | Walk-forward on NSE data measures it, not assumed |

## Not Covered / Phase 2

- Option-chain IV cross-check (Breeden-Litzenberger P(strike ITM)) to validate cheapness.
- Broker-side historical daily data via API instead of yfinance if yfinance gaps appear.
