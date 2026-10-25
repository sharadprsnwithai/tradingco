# Monthly Option-Selling Forecast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Monthly zero-shot forecast (direction, range, volatility probability) for 13 NSE assets feeding a Telegram digest the user trades from manually on the 1st, with a walk-forward calibration gate controlling whether "naked" advice may appear.

**Architecture:** Replace the fake TF stub pipeline with pretrained Chronos (zero-shot Monte Carlo sampling). Per asset, 30 sampled daily paths → stats (terminal bands, max-touch envelope, direction/vol probabilities) → deterministic rule mapping → digest message → Telegram + JSON history. Walk-forward backtest over ≥4y produces measured coverage that gates NAKED-OK advice.

**Tech Stack:** Python 3.11 (the local interpreter), `chronos-forecasting` (amazon/chronos-t5-small, CPU, `num_samples=30`), torch (CPU), yfinance, pandas/numpy, requests (Telegram), pytest.

**Spec:** `docs/superpowers/specs/2026-10-26-monthly-option-forecast-design.md` (and prior spec `2026-10-25-forecast-range-prediction-design.md` for the JSON contract it reuses).

## Global Constraints

- Python 3.11 availability; keep stdlib + the 5 libs above. No ML training code, no TensorFlow, no new services.
- Determinism: every stochastic step seeded (`torch.manual_seed`, numpy seed); reruns on the same date produce identical output.
- All probability numbers in output must originate from model samples or measured backtest coverage. Never hardcode a probability.
- Price data: one adjusted-close frame per asset used for BOTH model input and calibration; strike-zone levels converted back to prices via today's adjustment factor.
- Touch/envelope semantics: NAKED-OK strike zones come from path max/min quantiles, never terminal quantiles.
- Digest degrades to SPREAD-ONLY (never invents numbers) on: missing calibration, stale calibration (>31 days), missing model cache + HF unreachable, missing/corrupt asset CSV.
- Scope: 13 assets from `download_nse.py` SYMBOLS; per-asset failures don't kill the run (asset listed UNAVAILABLE in digest).
- Message sent to Telegram directly by Python from config (no Java dependency). Java side untouched.
- Delete (no reroute, no replacement service): `scripts/forecast/train_model.py`, `forecast_api/` directory, `scripts/forecast/run_monthly.sh` shell wrapper, any docker-compose `forecast_api` service block, `model_artifacts/` usage. Root-level `test_download_fails_first.py` and `test_forecast_api_contract.py` are updated/deleted accordingly.

## Review Focus

1. **Degenerate price frame** (a CSV with gaps, NaN closes, or a long constant close): no NaN can leak into quantiles — asset is flagged UNAVAILABLE, other assets continue. Test in Task 2.
2. **HF hub unreachable on first run with no cached weights:** produces SPREAD-ONLY placeholder digest with the error surfaced, exits 0, no invented numbers. Test in Task 6.
3. **Stale/missing `calibration.json`:** global SPREAD-ONLY, message states calibration age. Test in Task 4.
4. **Telegram send failure (network down):** digest still written to `data/forecast/latest.json` and `data/forecast/latest_digest.txt`; script exits 0; predict results unaffected. Test in Task 5.
5. **First trading day of month with stale CSV/weekend edge:** if the newest close in CSV is older than 5 calendar days, the digest says "DATA STALE (age=N days)" and proceeds; it does not silently forecast on old data. Test in Task 1.

---

### Task 1: Normalized data loader

**Files:**
- Modify: `scripts/forecast/download_nse.py` (rewrite the `download()` body)
- Create: `scripts/forecast/data_loader.py`
- Test: `tests/forecast/test_data_loader.py`

**Interfaces:**
- Produces: `load_price_frame(symbol: str, data_dir: Path = DATA_DIR) -> pd.DataFrame` with columns `[date, open, high, low, close, volume]`, sorted by date, adjusted throughout, and `adj_factor: pd.Series` (AdjClose/Close per row) exported as `get_adj_factor(df) -> pd.Series`. `data_age_days(symbol) -> int` (−1 if missing). DATA_DIR = `Path("data/forecast/nse_daily")`.

- [ ] **Step 1: Write the failing test**

```python
# uses a fixture CSV written by the test: date,open,high,low,close,adjclose
def test_load_price_frame_normalizes_columns(tmp_path):
    df = load_price_frame("TCS", data_dir=tmp_path)
    assert list(df.columns) == ["date", "open", "high", "low", "close", "volume"]
    assert df["close"].is_numeric() and not df["close"].isna().any()
    assert df.index.is_monotonic_increasing

def test_data_age_days_missing_is_minus_one(tmp_path): ...
```

- [ ] **Step 2: Run test to verify it fails** — `pytest tests/forecast/test_data_loader.py -v` → FAIL.

- [ ] **Step 3: Implement `data_loader.py`.** Read the stored CSV; store CSVs from yfinance with `auto_adjust=True, progress=False` then normalize: lowercase columns, `Date`→`date` index dropped into a column; volume may be missing → 0. `adj_factor = adjclose / close`. Raise `PriceDataError` (new exception) on unparseable/empty frame.

- [ ] **Step 4: Update `download_nse.py`** to use `auto_adjust=True, progress=False` and write the normalized frame via `load_price_frame`-equivalent normalization (reuse the function; store `adjclose` column too).

- [ ] **Step 5: Replace `test_download_fails_first.py`** with an updated contract: downloading any symbol produces a CSV with lowercase columns and a non-empty `close` (network-dependent test, allowed skip when offline: `pytest.mark.skipif`). Delete `test_forecast_api_contract.py` (its Flask contract disappears in Task 6).

- [ ] **Step 6: Run tests → PASS; commit** `feat: normalized price data loader` (files: `scripts/forecast/data_loader.py`, `scripts/forecast/download_nse.py`, both tests).

### Task 2: Chronos Monte Carlo core + per-asset stats

**Files:**
- Create: `scripts/forecast/forecast_core.py`
- Test: `tests/forecast/test_forecast_core.py`

**Interfaces:**
- Produces:
  - `load_model(cache_dir: Path) -> Any` — `chronos` T5 model via `ChronosPipeline`; downloads once into `cache_dir`, then always local-from-cache; `HfModelUnavailable` exception if download fails and cache empty.
  - `forecast_asset(df: pd.DataFrame, model, horizon: int = 21, n_paths: int = 30) -> dict` — samples `n_paths` paths of `horizon` daily steps, returns:
    ```python
    {"median": float, "term80": (lo,hi), "term95": (lo,hi),       # expiry-close quantiles
     "touch95": (low, high),                                     # per-path max/min q90/q10 (95% widest used: use q05/q95 across path-extremes → (min q05, max q95))
     "p_up": float, "p_down": float, "p_flat": float,            # terminal vs last close, ±1% deadband, sums to 1
     "p_volexp": float,                                          # share of paths whose horizon return-std exceeds trailing-21d std
     "last_close": float, "price_level_factor": float}           # latest adj_factor value
    ```
    All prices computed on adjusted close; caller converts zones via `price_level_factor`. Deadband constant `FLAT_DEADBAND = 0.01` module-level.
- Consumes: `load_price_frame` (Task 1); model injected (stub in tests).

- [ ] **Step 1: Write failing tests** — stub model injected (returns a fixed synthetic path matrix `(n_paths, horizon)`), assert: keys present; `p_up+p_down+p_flat == 1±1e-9`; with constant historical std and tiny sampled std, `p_volexp == 0.0`; `term95` wider than `term80`; monotonicity `touch95` ≥ `term95` for the same synthetic paths; zero-variance input frame (all closes equal) raises `PriceDataError`.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement.** Path extraction: `pipeline.predict(context, prediction_length=horizon, num_samples=n_paths)` → tensor `(1, n_paths, horizon)`. Quantiles via `np.quantile`. Envelope: per-path `max(axis=1)` / `min(axis=1)`, then `q95` / `q05` across paths. Determinism: seed set by caller (orchestrator passes `torch.manual_seed(seed)` before calls).
- [ ] **Step 4: Run → PASS; commit** `feat: chronos forecast core with terminal + touch stats`.

### Task 3: Verdict rules + NAKED-OK gate

**Files:**
- Create: `scripts/forecast/rules.py`
- Test: `tests/forecast/test_rules.py`

**Interfaces:**
- Produces:
  - `verdict(stats: dict) -> dict` with `{"direction": "BULLISH|BEARISH|NEUTRAL|SKIP", "structure": str, "ce_zone": float|None, "pe_zone": float|None}` — `ce_zone = touch95_high * price_level_factor`, `pe_zone = touch95_low * price_level_factor`. Thresholds (module constants, spec C5): `p_up > 0.60` BULLISH → structure "call bull spread (short leg near CE zone)"; `p_down > 0.60` BEARISH mirrored; else NEUTRAL → "sell BOTH ~15d: CE ≥ {ce}, PE ≤ {pe}"; `p_volexp > 0.50` appends " vol↑: prefer spreads or widen" to structure.
  - `naked_ok(calibration: dict | None) -> tuple[bool, str]` — False + reason "no calibration", "calibration stale (age=N days)" (> 31), or True with reason "95-band coverage {c:.1%}, dir LCB {l:.1%}". Passes when `coverage_95_touch >= 0.93` AND `direction_pass_lower_bound >= 0.55` (calibrate.py stores these two fields precomputed so rules.py stays arithmetic-only).

- [ ] **Step 1: Failing tests** — table-driven: (0.47/0.41/0.12, vol 0.3) → NEUTRAL BOTH; (0.71/…) → BULLISH call spread; (0.71 p_down side) → BEARISH; vol 0.6 → structure contains "vol↑"; calibration None → (False, "no calibration"); calibration with age 40 → stale; passing dict → True.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement.** **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat: structure verdict rules and naked-calibration gate`.

### Task 4: Walk-forward calibration

**Files:**
- Create: `scripts/forecast/calibrate.py`
- Test: `tests/forecast/test_calibrate.py`

**Interfaces:**
- Consumes: `forecast_asset`, `load_price_frame`, `load_model` (stub in tests), `load_price_frame` trailing windows sliced by date.
- Produces CLI `python -m scripts.forecast.calibrate [--months 48] [--seed 42]` writing `data/forecast/calibration.json`:
  ```json
  {"as_of": "YYYY-MM-DD", "n_obs": 480, "coverage_95_touch": 0.94, "coverage_80_touch": 0.81,
   "direction_pass_rate": 0.58, "direction_pass_lower_bound": 0.55, "per_asset": {"TCS": {...}}}
  ```
  Winson (Wilson score interval at 95%) computes the lower bound. Touch measured on adjusted close vs touch95 zone at each walk-forward month (predict at month-end t using trailing data only; score against following month's adjusted min/max/close).
- Uses `n_paths=30`, `horizon=21` fixed (same as live). Prints progress per asset.

- [ ] **Step 1: Failing test** with synthetic repo of 2 assets × 60 months and a stub model whose paths have known quantiles → assert `calibration.json` fields exist, `n_obs == 48 * 2`, per-asset counts sum to `n_obs`, Wilson bound ≤ pass rate.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement.** **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat: walk-forward calibration with measured coverage`.

### Task 5: Digest builder + Telegram send

**Files:**
- Create: `scripts/forecast/digest.py`
- Test: `tests/forecast/test_digest.py`

**Interfaces:**
- Consumes: `verdict`, `naked_ok`, `forecast_core` stats dicts.
- Produces:
  - `build_digest(assets: dict[str, dict], calibration) -> str` — 3 trading assets first (full blocks, spec C4 format with NAKED-OK/SPREAD-ONLY flag + calibration reason), remaining 10 as one-line verdicts; UNAVAILABLE assets listed as `SYMBOL: UNAVAILABLE (no data)`. Message ends with period line `"` + `generated_at` IST.
  - `send_telegram(text: str) -> bool` — reads env `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`; POST to `api.telegram.org`; False on any exception.

- [ ] **Step 1: Failing tests** — fixture stats dicts → exact assertion that both `NAKED-OK` and `SPREAD-ONLY` intents render; an UNAVAILABLE asset never crashes; `send_telegram` mocked (monkeypatched `requests.post`) success/exception paths.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement.** **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat: digest message builder + telegram sender`.

### Task 6: Orchestration CLI + degradation behavior

**Files:**
- Create: `scripts/forecast/run_monthly.py`
- Test: `tests/forecast/test_run_monthly.py`
- Modify: root `README.md` (replace forecast-run instructions)

**Interfaces:**
- Consumes everything above. CLI: `python -m scripts.forecast.run_monthly [--seed 42] [--no-send]`.
- Flow: load frame → check `data_age_days` (≥5 → prepend "DATA STALE (age=N)") → `torch.manual_seed(seed)` → `forecast_asset` per asset → digest → write `data/forecast/latest.json` (prior-spec contract: `generated_at`, `period`, `assets[]`) + `data/forecast/history/forecast_YYYY-MM.json` + prune files older than 1460 days → write `data/forecast/latest_digest.txt` → `send_telegram` (unless `--no-send`). NEVER raising to the operator for asset-level or send-level failures; HF/cache failure → still writes digest with all assets `UNAVAILABLE (model unavailable)` and reasons.

- [ ] **Step 1: Failing tests** (the Review Focus lines): stub all network + model calls with monkeypatches.
  - HF-unavailable → exit 0, `latest.json` exists, digest contains "SPREAD-ONLY".
  - One corrupt CSV → other 12 assets present in `latest.json`, corrupt one UNAVAILABLE.
  - Telegram-down stubs raising → `latest.json` + `latest_digest.txt` still written, exit 0.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement.** **Step 4: Run → PASS.**
- [ ] **Step 5: Delete** `train_model.py`, `forecast_api/` (Dockerfile + forecast_service.py), `run_monthly.sh`; remove the forecast-api service block from `docker-compose.yml`; `git rm` stale references.
- [ ] **Step 6: Commit** `feat: monthly digest orchestrator; remove stub services`.

### Task 7: Live smoke run + calibration evidence

**Files:**
- Create: `data/forecast/calibration.json` (output artifact of a real Superset run)
- Modify: `README.md` (add: run instructions, one-time `pip install chronos-forecasting` line)

- [ ] **Step 1:** `python -m scripts.forecast.download_nse` → 13 CSVs with lowercase columns; any symbol that Yahoo blocks goes UNAVAILABLE (record which).
- [ ] **Step 2:** `python -m scripts.forecast.calibrate --months 48` → calibration.json with real coverage numbers; commits as artifact `chore: initial calibration baseline`.
- [ ] **Step 3:** `python -m scripts.forecast.run_monthly --no-send` → `latest.json` + digest text written; manually paste the digest into the chat for the user to inspect.
- [ ] **Step 4:** `pytest tests/forecast -v` → all PASS.
- [ ] **Step 5: Commit** `chore: baseline calibration + smoke evidence` (calibration.json only, not CSVs — CSVs gitignored in `data/`).

---

## Self-Review Notes

- Spec coverage: G1–G7 map to Tasks 1/6 (& 4 for G7 retention prune), 2 (G2 bands/probs), 3 (G3 verdict + G5 gate), 4 (G5 calibration), 5 (G6 Telegram), 4/6 archive (G7); NG/AG (no training/deletion) → Task 6.5; C2 determinism in Task 2/6 seeds; C4 format in Task 5.
- Type consistency checked: `stats` dict keys in Task 2 = consumed by Task 3/5; `calibration.json` keys in Task 4 = gate input keys in Task 3; `naked_ok` returns tuple used by digest/Task 5+6.
- Proportion: plan ≈ spec length; no bodies beyond nondeterministic algorithms (envelope reduction, Wilson bound).
