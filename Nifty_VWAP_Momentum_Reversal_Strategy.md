# Strategy Spec: Nifty 9:30 Baseline + 5-Min VWAP Cross (Intraday Options Buying)

## 1. Objective
An intraday, directional **options-buying** strategy on Nifty. A market bias (bullish/bearish/neutral) is
established by comparing an 11:00 AM snapshot of Nifty spot and PCR (Put-Call Ratio) against a single
9:30 AM baseline. Once a bias is confirmed, entry is triggered only after Nifty's 5-minute candle
**closes** on the correct side of its intraday session VWAP, buying the corresponding ATM CE/PE.

---

## 2. Data Points Required

| Data Point | Source | Notes |
|---|---|---|
| Nifty Futures (current month) price | Kite Connect LTP/Quote API | Sampled at 9:30 AM (baseline) and continuously from 11:00 onward |
| PCR (Put-Call Ratio) | Kite Connect option chain (OI-based: Total Put OI / Total Call OI) | Computed for current weekly expiry, ATM ± 4 strikes (9 strikes each side of ATM) |
| Nifty 5-min OHLC candles | Kite Connect historical/candle API | Used to compute VWAP and detect candle close cross |
| Session VWAP (5-min) | Kite-standard intraday VWAP | See formula below |

### VWAP — Kite/Standard Calculation
**Kite Connect does not provide VWAP as a ready-made field anywhere** — not in `quote()`, not in
`quote/ohlc`, not in the historical candle API, and not in the `KiteTicker` websocket feed. The
`average_price` field returned by Kite is the day's cumulative **Average Traded Price (ATP)**, which is
a different value from VWAP and should not be substituted for it (confirmed on Kite's own developer
forum — ATP and chart VWAP diverge in practice). VWAP must be **computed by this system** from raw
OHLCV data pulled via the historical candle API.

> ⚠️ **Confirmed distinction — volume is instrument-specific:** Kite's historical API returns **zero
> volume** for the Nifty 50 index itself (token 256265) at every interval, since an index isn't a
> traded instrument. **Nifty Futures**, however, is a traded contract and its 5-min historical candles
> come with real, non-zero volume — this is the instrument this strategy already uses throughout
> (confirmed earlier: "I am good with nifty futures"). So as long as the system is pulling 5-min OHLCV
> for the **current-month Nifty Futures instrument token** (not the Nifty 50 index token), the volume
> field is valid and usable for VWAP. Do not accidentally point the historical data call at the index
> token — it will silently return volume=0 and break the VWAP calculation.

VWAP formula, using 5-minute Nifty Futures candles from the start of the session (9:15 AM):

```
Typical Price (TP) for a candle = (High + Low + Close) / 3
VWAP (as of candle N) = Σ(TP_i × Volume_i) for i=1..N-1   ÷   Σ(Volume_i) for i=1..N-1
```

> ⚠️ **Important:** VWAP for evaluating candle N uses candles 1 through N-1 only. The current candle
> is NOT included in its own VWAP calculation. This prevents a self-referential check where the
> candle's close influences the very VWAP it's being compared against. VWAP is recalculated after
> each candle closes, but only using completed prior candles.
- Resets every trading day at 9:15 AM (session start) — no carry-over from previous day.
- Maintained incrementally in-memory: track `cumulativeTPxVol` and `cumulativeVolume`, reset at 9:15 AM. After each 5-min candle closes, add its TP×Vol and Vol to the accumulators, then VWAP = cumulativeTPxVol / cumulativeVolume.
- Data source: Kite Connect historical/candle API (`historical_data`) for the current-month **Nifty
  Futures instrument token**, fetched on a rolling basis.

**Confirmed:** All candles, VWAP, and the cross-check are run entirely on **Nifty Futures (current
month contract)** — not spot. This means:
- `Nifty_930` and `Nifty_1100` snapshots (Section 3) are Nifty Futures LTP, not spot LTP.
- 5-min OHLCV candles used for VWAP are Nifty Futures candles.
- The "candle closes above/below VWAP" trigger (Section 4) is evaluated on the Nifty Futures chart.
- PCR still comes from the Nifty options chain (unaffected by this choice).
- ATM strike for CE/PE selection is still based on Nifty spot (or futures — see open question 8 below),
  since option chains are quoted against spot in practice for most brokers/exchanges.

---

## 3. Snapshot & Bias Logic

### Step 1 — 9:30 AM Baseline
- Capture `Nifty_930` = Nifty Futures LTP at 9:30 AM.
- Capture `PCR_930` = PCR at 9:30 AM.

### Step 2 — 11:00 AM Recheck
- Capture `Nifty_1100` = Nifty Futures LTP at 11:00 AM.
- Capture `PCR_1100` = PCR at 11:00 AM.

### Step 3 — Bias Determination (evaluated once, at 11:00 AM)

**Bullish bias** (long/CE only) if **both** of the following are true:
- `Nifty_1100 > Nifty_930`
- `PCR_1100 > PCR_930`

**Bearish bias** (short/PE only) — mirror logic — if **both** of the following are true:
- `Nifty_1100 < Nifty_930`
- `PCR_1100 < PCR_930`

**No trade / Neutral** if the conditions are mixed (e.g., price up but PCR down). No positions are
taken for the day in this case.

> Bias is locked once determined at 11:00 AM — it does **not** get re-evaluated later in the day (please
> confirm if you'd like a periodic re-check instead, e.g., every 15 min after 11:00 until a valid entry
> trigger fires or a cutoff time is reached).

---

## 4. Entry Trigger (only if a bias was confirmed at 11:00 AM)

After 11:00 AM, monitor Nifty's 5-minute candles against the session VWAP:

### If Bullish Bias:
- Wait for a 5-minute candle that satisfies **all three** conditions:
  1. **Low < VWAP** (candle dips below VWAP intracandle)
  2. **Close > VWAP** (reclaims and closes above VWAP)
  3. **Close > Open** (green/bullish candle — confirms buying pressure, not just a wick reclaim)
- On this confirmed candle → **Buy ATM CE** (current weekly expiry) at market/LTP.

### If Bearish Bias:
- Wait for a 5-minute candle that satisfies **all three** conditions:
  1. **High > VWAP** (candle pokes above VWAP intracandle)
  2. **Close < VWAP** (rejects and closes below VWAP)
  3. **Close < Open** (red/bearish candle — confirms selling pressure, not just a wick rejection)
- On this confirmed candle → **Buy ATM PE** (current weekly expiry) at market/LTP.

### Entry Filters / Guardrails
- **Maximum 3 entries per day** for this strategy, combined across CE and PE signals (e.g., if bias is
  bullish, up to 3 CE entries total; entries stop once 3 trades — win, loss, or a mix — have been taken,
  even if further valid VWAP reclaim/rejection candles occur the same day).
- Each of the 3 entries is independent: enter on a valid candle → run to target/SL/EOD square-off → if
  slots remain and another valid candle forms, take the next entry.
- **No new entries after 1:00 PM** on expiry day (Thursday) — ATM options lose 30-50% of premium in
  the last 2 hours due to theta decay. A 20-point SL on an option priced at ₹80 is a 25% stop, which
  fundamentally changes the risk profile.
- **No new entries after 3:00 PM** on non-expiry days, even if slots remain and a valid signal candle
  forms.
- ATM strike is recalculated at the moment of each entry signal (not fixed at 9:30/11:00 AM), based on
  Nifty spot price at that instant. Option chains are spot-quoted.

---

## 5. Exit Rules

| Parameter | Value |
|---|---|
| Target | **+40 points** on option premium |
| Initial Stop Loss | **-20 points** on option premium |
| Trailing SL | After **2 completed candles** post-entry, SL trails to **Nifty Futures VWAP** (see logic below) |
| Trade type | Intraday only — mandatory square-off of any active position by **3:14 PM** regardless of target/SL status |
| Position sizing | **2 lots** per entry (fixed) — Nifty lot size = 65, so 130 qty per entry |
| Max entries/day | **3** (combined CE+PE, whichever bias is active) |
| Risk per trade | 130 qty × ₹20 SL = ₹2,600 per entry |
| Worst-case daily | 3 × ₹2,600 = ₹7,800 (if all 3 hit SL) |

### Trailing SL Logic (VWAP-based)

After entry, the stop loss has two exit mechanisms: the fixed premium SL and the VWAP cross exit.

1. **Candles 1-2 post-entry (grace period):** SL stays at the initial -20 points from entry price. No trailing yet — the trade needs room to breathe.

2. **From candle 3 onward — two simultaneous exit rules:**

   **Rule A: VWAP cross exit (early loss cut / profit protect)**
   - **Bullish (CE) trade:** If a 5-min candle **closes below VWAP** after the 2-candle grace period → **exit immediately** at market/LTP, regardless of P&L. The VWAP reclaim that triggered entry has failed — don't wait for the full -20 SL.
   - **Bearish (PE) trade:** If a 5-min candle **closes above VWAP** → **exit immediately** at market/LTP.
   - This is a directional signal exit, not a P&L-based exit. Even if the loss is only 5 points, the setup is broken — exit and preserve capital for the next valid signal.

   **Rule B: Trailing SL (favorable direction only)**
   - **Bullish (CE) trade:** SL trails upward to VWAP of the most recently closed candle. If VWAP has moved above entry-underlying, convert the favorable move to premium points via estimated delta and update SL.
   - **Bearish (PE) trade:** Mirror logic — SL trails downward as VWAP moves favorably.
   - **Breakeven lock:** If trailed SL moves into profit territory, lock to at least **+5 points** (breakeven + buffer) — never let a winning trade turn into a loss.
   - **Hard rule:** Trailing SL only moves **in the favorable direction**. It never retreats.

3. **Exit priority:** If both Rule A (VWAP cross) and Rule B (trailing SL) trigger on the same candle, Rule A fires first — exit at market immediately. The trailing SL is a safety net, not the primary exit.

**Example scenarios:**

| Scenario | Action | Reason |
|---|---|---|
| Entry at ₹120, after 3 candles premium = ₹115, candle closes below VWAP | **Exit at ₹115** (-5 pts) | VWAP cross against you — setup broken, exit early |
| Entry at ₹120, after 3 candles premium = ₹130, candle closes below VWAP | **Exit at ₹130** (+10 pts) | VWAP cross — protect profit, don't let winner reverse |
| Entry at ₹120, after 3 candles premium = ₹125, candle closes above VWAP | **Hold**, SL trails to ₹125 (breakeven +5) | Trend intact, SL locked to breakeven |
| Entry at ₹120, after 2 candles premium = ₹108, candle closes below VWAP | **Hold until -20 SL** | Grace period — VWAP cross exit not active yet |

**Confirmed:** Target and Stop Loss are measured in **option premium points**, not Nifty index points
(e.g., if ATM CE is bought at ₹120, target is ₹160, initial SL is ₹100 — a flat ₹40/20 premium move,
regardless of the option's delta or the corresponding index move). The trailing SL converts VWAP movement
on the underlying to premium movement via estimated delta.

---

## 6. Risk & Operational Notes
- If PCR data or option chain is momentarily unavailable at 9:15/9:30/11:00, hold last known value for
  up to 2 minutes before treating the snapshot as failed (no trade that day).
- If Nifty gaps significantly at open (e.g., >0.5%), consider whether the 9:15 snapshot is still valid
  — currently no gap filter is applied. *(flag if you want one, similar to your gap-risk companion spec
  on the other intraday strategy)*
- No averaging / no partial exits — single entry, single exit per signal.
- Strategy is one-directional per day: once a CE trade is taken (or skipped due to no bias), no PE trade
  is taken the same day and vice versa (bias is locked for the day, not per-signal).

---

## 7. Risk Management

### Execution & System Risk
- **Use real exchange-side SL orders, not software-monitored stops.** Place an actual SL-M or SL-L
  order immediately on entry so the stop holds even if the bot process crashes, the API disconnects, or
  network drops — do not rely purely on the bot polling LTP and issuing an exit order reactively.
- **Slippage buffer on entries.** Use a marketable limit order (LTP + small buffer, e.g., 1-2 points)
  instead of a pure market order for ATM CE/PE entries, to avoid slippage eating into the 20-point SL
  during fast VWAP-reclaim moves.
- **Reconciliation after every order.** Confirm the actual fill (price + quantity) via order/trade
  history before treating a position as "live" in the bot's internal book — don't assume the order
  request succeeded as intended.

### Data-Quality Risk
- **Staleness guard on VWAP/candle/PCR data.** If a 5-min Nifty Futures candle fetch or PCR fetch fails,
  times out, or returns stale/zero data, skip that evaluation cycle rather than acting on bad inputs.
- **Sanity-bound snapshots.** Reject a 9:30 AM or 11:00 AM snapshot (Nifty Futures price or PCR) if it's
  wildly discontinuous from the last known good tick (e.g., a bad print/outlier), rather than locking a
  bias off corrupted data.
- **Confirm historical data source is the Nifty Futures instrument token**, not the Nifty 50 index token
  (256265) — the index always returns volume=0 and would silently break VWAP (see Section 2 note).

### Trade-Level Risk
- **Daily kill-switch on consecutive losses.** Stop taking further entries for the day after **2
  consecutive SL hits**, even if a 3rd entry slot remains — a run of 2 losses is a signal the day's
  regime doesn't fit the strategy, and burning the 3rd slot into the same conditions compounds the loss.
- **Hard daily max-loss cap.** Stop entries once cumulative realized P&L for the day breaches
  **-₹5,000** (roughly 2× risk per trade), even if fewer than 2 consecutive SLs have technically been hit
  (covers cases like 1 large loss + 1 small loss that individually don't trigger the consecutive-loss
  rule). This sits below the worst-case ₹7,800 to provide a buffer.
- **Event/gap filter.** Skip the day, or at minimum the first 15-30 minutes of signal evaluation, around
  known event risk — RBI policy announcements, Union Budget day, or a large overnight gap (>0.5-0.7%
  vs. previous close). Bias and VWAP levels formed immediately after a shock tend to be unreliable.
- **Expiry-day carve-out.** On the Nifty weekly expiry day (Thursday), no new entries after 1:00 PM.
  Faster theta decay, wider spreads late session, and sharper gamma swings make late entries
  unattractive. Consider skipping expiry day entirely if backtests show poor results.

### Sizing & Capital Risk
- **Fixed 2-lot sizing for v1.** Each entry = 2 lots × 65 qty = 130 qty. Risk per trade = ₹2,600
  (130 × ₹20 SL). This is simple, auditable, and appropriate for initial live validation.
- **Dynamic sizing for v2** (post live validation): size each entry so that SL-in-rupees × quantity
  stays within a fixed % of capital (e.g., 0.5-1% per trade), rather than a flat 2-lot rule.
- **Hard daily max-loss cap** at **-₹5,000**, independent of the 2-SL kill-switch, as a final backstop.
  Even if 2 consecutive SLs haven't technically been hit, stop entries once cumulative realized P&L
  breaches this threshold (covers 1 large + 1 small loss scenario).
- **Worst-case daily loss at current sizing**: 3 entries × 2 lots × 65 qty × ₹20 SL = ₹7,800. The
  ₹5,000 max-loss cap triggers before this worst case is reached.

---

## 9. Resolved Decisions

| # | Question | Resolution |
|---|---|---|
| 1 | **Bias re-check** | Locked once at 11:00 AM — no periodic re-evaluation |
| 2 | **Entry cutoff** | Single hard cutoff: no entries after 3:00 PM (non-expiry), 1:00 PM (expiry Thursday) |
| 3 | **Position sizing** | Fixed 2 lots (130 qty) for v1. Dynamic sizing deferred to v2 |
| 4 | **Expiry selection** | Current weekly expiry confirmed |
| 5 | **ATM strike basis** | Nifty spot (option chains are spot-quoted) |
| 6 | **PCR strike range** | ATM ± 4 strikes (9 strikes each side) |
| 7 | **VWAP calculation** | Use VWAP of candles 1..N-1 (exclude current candle) to avoid self-reference |
| 8 | **Entry candle filter** | Add green/red candle filter (Close > Open for bullish, Close < Open for bearish) |
| 9 | **Trailing SL** | After 2 candles grace period: (A) VWAP cross against you = immediate exit at market, regardless of P&L. (B) VWAP trail in favorable direction only, breakeven lock at +5. Rule A fires before Rule B on same candle. |
