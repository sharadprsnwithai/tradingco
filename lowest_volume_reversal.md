# Intraday Strategy: Lowest Volume Reversal & Continuation

A structured breakdown of the intraday price action and volume trading strategy by Kushal Varshney and Sagar Kummar.

---

## 1. Strategy Overview

* **Core Concept:** Enter high-probability intraday setups with an ultra-tight stop loss and large risk-to-reward potential ($1:2$ up to $1:10+$) by identifying volume exhaustion on pullback candles.
* **Timeframe:** 5-minute chart.
* **Instruments:** F&O listed stocks (high liquidity cash segment recommended for beginners).

---

## 2. Stock Selection & Timing

| Step | Time / Action | Details |
| :--- | :--- | :--- |
| **Observation Phase** | 09:15 AM – 09:25 AM | Do not trade. Let the opening 10-minute volatility settle. |
| **Scanner Check** | 09:26 AM | Open NSE India and navigate to **Market Data > Equity & SME > Securities in F&O**. |
| **List Filter** | 09:26 AM | Check **Top Gainers** (for long setups) and **Top Losers** (for short setups). |
| **Trend & Sentiment** | Pre-Market / Open | Align trades with the major trend of Nifty and intraday market breadth (advances vs. declines). |

---

## 3. Setup Rules

### Long (Buy) Setup
1. **Initial Leg:** Stock shows strong upward momentum for at least 2–3 consecutive candles.
2. **Pullback (Stoppage):** Wait for a **Red (opposite) candle** to form.
3. **Volume Condition:** The red candle must have the **lowest volume bar** among recent preceding 5 min candles of the day.
4. **Trigger & SL:**
   * **Entry:** Buy immediately when price breaks above the **High** of this lowest-volume red candle.
   * **Stop Loss:** Place just below the **Low** of that same red candle.

### Short (Sell) Setup
1. **Initial Leg:** Stock drops steadily for at least 2–3 consecutive candles.
2. **Pullback (Stoppage):** Wait for a **Green (opposite) candle** to form.
3. **Volume Condition:** The green candle must show the **lowest volume bar** relative to preceding 5 min candles of the day.
4. **Trigger & SL:**
   * **Entry:** Sell/Short immediately when price breaks below the **Low** of this lowest-volume green candle.
   * **Stop Loss:** Place just above the **High** of that same green candle.

### Setup Filter / Disqualification
* **Avoid Exhausted Moves:** If the very first 5-minute candle moves 5%–6% immediately, skip the stock as the major expansion is already done.

---

## 4. Risk Management & Trade Execution

* **Daily Risk Limit:** 1% of total account capital per day.
* **Risk Per Trade (RPT):** Split daily risk evenly across predefined trade counts (e.g., ₹1,000 daily risk divided into 2 trades = ₹500 RPT).
* **Target 1:** Minimum **1:2 Risk-to-Reward (RR)**. Book 50% position here.
* **Trailing Stop:** Once 1:2 is hit, immediately move the Stop Loss to **Cost (Break-even)**.
* **Hard Exit:** Close all remaining positions strictly at **03:00 PM** without manual discretionary interference.