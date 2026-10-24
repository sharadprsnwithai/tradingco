"""
Comprehensive Backtest Engine for Intraday Option Selling Strategy:
Schedule:
  - Monday:    NIFTY 50 (1-DTE)
  - Tuesday:   NIFTY 50 (0-DTE)
  - Wednesday: SENSEX   (1-DTE)
  - Thursday:  SENSEX   (0-DTE)
  - Friday:    Idle (No trade)

Rules:
  - 09:45 AM IST: Buy 20-strike OTM CE and PE hedges (+1000/-1000 on Nifty, +2000/-2000 on Sensex).
  - 10:00 AM IST: Sell ATM CE and ATM PE straddle.
  - Risk Control: Independent 30% stop-loss on entry premium (SL = Entry * 1.30) on each short leg.
  - Option A: If one leg hits 30% SL, exit that leg immediately. Leave the other short leg and hedges running.
  - 15:00 PM IST: Mandatory square-off of all active short legs and long hedges.
  - Sizing: Configurable lot count (default 2 lots).
  - Data: Actual 5-minute intraday historical candle data from Yahoo Finance (^NSEI, ^BSESN, ^INDIAVIX).
"""

import math
import os
import sys
from datetime import datetime, date, time, timedelta
import pandas as pd
import numpy as np
from scipy.stats import norm
import yfinance as yf

# -----------------------------------------------------------------------------
# Black-Scholes Pricing Model
# -----------------------------------------------------------------------------
def get_intraday_tte(trade_date, current_time, expiry_date):
    """
    Computes time-to-expiry in annualized years based on 375 trading minutes per day.
    Market closes at 15:30 IST.
    """
    days_diff = (expiry_date - trade_date).days
    cur_mins = current_time.hour * 60 + current_time.minute
    close_mins = 15 * 60 + 30  # 15:30 IST

    mins_remaining_today = max(0, close_mins - cur_mins)
    frac_today = mins_remaining_today / 375.0

    if days_diff <= 0:
        trading_days_remaining = max(0.005, frac_today)
    else:
        trading_days_remaining = (days_diff - 1) + frac_today

    return max(trading_days_remaining / 365.25, 1e-5)


def bs_option_price(spot, strike, tte, iv, opt_type='CE', r=0.065):
    """Calculates Black-Scholes European option premium."""
    if spot <= 0 or strike <= 0 or tte <= 0:
        return 0.05

    sigma = max(iv, 0.05)
    d1 = (math.log(spot / strike) + (r + 0.5 * sigma**2) * tte) / (sigma * math.sqrt(tte))
    d2 = d1 - sigma * math.sqrt(tte)

    if opt_type == 'CE':
        price = spot * norm.cdf(d1) - strike * math.exp(-r * tte) * norm.cdf(d2)
    else:
        price = strike * math.exp(-r * tte) * norm.cdf(-d2) - spot * norm.cdf(-d1)

    return max(0.05, float(price))


# -----------------------------------------------------------------------------
# Data Loader from Yahoo Finance
# -----------------------------------------------------------------------------
def fetch_market_data():
    print("=================================================================")
    print("Fetching actual intraday data from Yahoo Finance (60-day 5m)...")
    print("=================================================================")

    # 1. NIFTY 50 (^NSEI)
    nifty_raw = yf.download('^NSEI', period='60d', interval='5m', progress=False)
    if nifty_raw.empty:
        raise ValueError("Failed downloading NIFTY data")
    if isinstance(nifty_raw.columns, pd.MultiIndex):
        nifty_raw.columns = [c[0] for c in nifty_raw.columns]

    df_nifty = nifty_raw.dropna(subset=['Open', 'High', 'Low', 'Close']).copy()
    df_nifty['CandleTime'] = df_nifty.index.tz_convert('Asia/Kolkata')
    df_nifty['Date'] = df_nifty['CandleTime'].dt.date
    df_nifty['Time'] = df_nifty['CandleTime'].dt.time
    df_nifty['Weekday'] = df_nifty['CandleTime'].dt.weekday

    # 2. SENSEX (^BSESN)
    sensex_raw = yf.download('^BSESN', period='60d', interval='5m', progress=False)
    if sensex_raw.empty:
        raise ValueError("Failed downloading SENSEX data")
    if isinstance(sensex_raw.columns, pd.MultiIndex):
        sensex_raw.columns = [c[0] for c in sensex_raw.columns]

    df_sensex = sensex_raw.dropna(subset=['Open', 'High', 'Low', 'Close']).copy()
    df_sensex['CandleTime'] = df_sensex.index.tz_convert('Asia/Kolkata')
    df_sensex['Date'] = df_sensex['CandleTime'].dt.date
    df_sensex['Time'] = df_sensex['CandleTime'].dt.time
    df_sensex['Weekday'] = df_sensex['CandleTime'].dt.weekday

    # 3. INDIA VIX (^INDIAVIX) for accurate historical daily implied volatility
    vix_raw = yf.download('^INDIAVIX', period='60d', interval='1d', progress=False)
    vix_map = {}
    if not vix_raw.empty:
        if isinstance(vix_raw.columns, pd.MultiIndex):
            vix_raw.columns = [c[0] for c in vix_raw.columns]
        for idx, row in vix_raw.iterrows():
            d = idx.date() if hasattr(idx, 'date') else idx
            vix_val = float(row['Close']) / 100.0
            if not math.isnan(vix_val) and vix_val > 0.05:
                vix_map[d] = vix_val

    print(f"Loaded {len(df_nifty)} candles for NIFTY, {len(df_sensex)} candles for SENSEX, {len(vix_map)} daily VIX quotes.")
    return df_nifty, df_sensex, vix_map


# -----------------------------------------------------------------------------
# Daily Simulation Engine
# -----------------------------------------------------------------------------
def simulate_day(day_df, instrument, mode, daily_iv, lots=2, move_to_breakeven=False):
    """
    Simulates one single day of execution:
      - 09:45 IST: Buy 20 OTM CE & PE hedges
      - 10:00 IST: Sell ATM CE & PE straddle
      - 30% independent SL on each short leg
      - move_to_breakeven: if True, when one leg hits SL, move other leg's SL to entry price
      - 15:00 IST: Square off all active legs
    """
    trade_date = day_df['Date'].iloc[0]
    strike_step = 50.0 if instrument == 'NIFTY' else 100.0
    hedge_distance = 20 * strike_step  # 1,000 pts Nifty, 2,000 pts Sensex

    # Lot sizes (NIFTY = 65, SENSEX = 20; 2 lots = 130 and 40)
    qty_per_lot = 65 if instrument == 'NIFTY' else 20
    total_qty = lots * qty_per_lot

    # Expiry date
    expiry_date = trade_date if mode == '0DTE' else trade_date + timedelta(days=1)

    t_hedge = time(9, 45)
    t_entry = time(10, 0)
    t_exit = time(15, 0)

    long_ce_strike, long_ce_entry = None, 0.0
    long_pe_strike, long_pe_entry = None, 0.0
    long_hedges_active = False

    short_ce_strike, short_ce_entry, short_ce_sl = None, 0.0, 0.0
    short_pe_strike, short_pe_entry, short_pe_sl = None, 0.0, 0.0
    short_ce_active, short_pe_active = False, False

    short_ce_exit_price, short_pe_exit_price = 0.0, 0.0
    short_ce_sl_hit, short_pe_sl_hit = False, False
    short_ce_exit_time, short_pe_exit_time = None, None

    long_ce_exit_price, long_pe_exit_price = 0.0, 0.0
    entered_straddle = False

    sorted_df = day_df.sort_values('CandleTime').copy()

    for _, row in sorted_df.iterrows():
        c_time = row['Time']
        spot_open = float(row['Open'])
        spot_high = float(row['High'])
        spot_low = float(row['Low'])
        spot_close = float(row['Close'])

        # -------------------------------------------------------------
        # 1. 09:45 AM IST - Buy Hedges (20 OTM CE & PE)
        # -------------------------------------------------------------
        if not long_hedges_active and c_time >= t_hedge and c_time < t_entry:
            base_atm = round(spot_open / strike_step) * strike_step
            long_ce_strike = base_atm + hedge_distance
            long_pe_strike = base_atm - hedge_distance

            tte_hedge = get_intraday_tte(trade_date, c_time, expiry_date)
            long_ce_entry = bs_option_price(spot_open, long_ce_strike, tte_hedge, daily_iv, 'CE')
            long_pe_entry = bs_option_price(spot_open, long_pe_strike, tte_hedge, daily_iv, 'PE')
            long_hedges_active = True

        # -------------------------------------------------------------
        # 2. 10:00 AM IST - Sell ATM Straddle
        # -------------------------------------------------------------
        if long_hedges_active and not entered_straddle and c_time >= t_entry and c_time < t_exit:
            atm_strike = round(spot_open / strike_step) * strike_step
            short_ce_strike = atm_strike
            short_pe_strike = atm_strike

            tte_entry = get_intraday_tte(trade_date, c_time, expiry_date)
            short_ce_entry = bs_option_price(spot_open, short_ce_strike, tte_entry, daily_iv, 'CE')
            short_pe_entry = bs_option_price(spot_open, short_pe_strike, tte_entry, daily_iv, 'PE')

            # 30% Stop Loss per leg
            short_ce_sl = short_ce_entry * 1.30
            short_pe_sl = short_pe_entry * 1.30

            short_ce_active = True
            short_pe_active = True
            entered_straddle = True

        # -------------------------------------------------------------
        # 3. Active Risk Monitoring (10:00 to 15:00) - Check 30% SL
        # -------------------------------------------------------------
        if entered_straddle and c_time >= t_entry and c_time < t_exit:
            tte_cur = get_intraday_tte(trade_date, c_time, expiry_date)

            # Check CE Stop-Loss (breached if market rallies to high)
            if short_ce_active:
                max_ce_price = bs_option_price(spot_high, short_ce_strike, tte_cur, daily_iv, 'CE')
                if max_ce_price >= short_ce_sl:
                    short_ce_active = False
                    short_ce_sl_hit = True
                    short_ce_exit_price = short_ce_sl
                    short_ce_exit_time = c_time
                    if move_to_breakeven and short_pe_active:
                        # Move surviving PE leg to breakeven (entry price)
                        short_pe_sl = short_pe_entry

            # Check PE Stop-Loss (breached if market crashes to low)
            if short_pe_active:
                max_pe_price = bs_option_price(spot_low, short_pe_strike, tte_cur, daily_iv, 'PE')
                if max_pe_price >= short_pe_sl:
                    short_pe_active = False
                    short_pe_sl_hit = True
                    short_pe_exit_price = short_pe_sl
                    short_pe_exit_time = c_time
                    if move_to_breakeven and short_ce_active:
                        # Move surviving CE leg to breakeven (entry price)
                        short_ce_sl = short_ce_entry

        # -------------------------------------------------------------
        # 4. 15:00 PM IST - Mandatory Square-off
        # -------------------------------------------------------------
        if entered_straddle and c_time >= t_exit:
            tte_exit = get_intraday_tte(trade_date, t_exit, expiry_date)

            # Square off surviving short legs
            if short_ce_active:
                short_ce_exit_price = bs_option_price(spot_close, short_ce_strike, tte_exit, daily_iv, 'CE')
                short_ce_active = False
                short_ce_exit_time = t_exit

            if short_pe_active:
                short_pe_exit_price = bs_option_price(spot_close, short_pe_strike, tte_exit, daily_iv, 'PE')
                short_pe_active = False
                short_pe_exit_time = t_exit

            # Square off long hedges
            if long_hedges_active:
                long_ce_exit_price = bs_option_price(spot_close, long_ce_strike, tte_exit, daily_iv, 'CE')
                long_pe_exit_price = bs_option_price(spot_close, long_pe_strike, tte_exit, daily_iv, 'PE')
                long_hedges_active = False

            break

    if not entered_straddle:
        return None

    # Calculate leg-by-leg P&L (per share)
    # Short legs: Profit = Entry - Exit
    pnl_short_ce = short_ce_entry - short_ce_exit_price
    pnl_short_pe = short_pe_entry - short_pe_exit_price

    # Long hedges: Profit = Exit - Entry
    pnl_long_ce = long_ce_exit_price - long_ce_entry
    pnl_long_pe = long_pe_exit_price - long_pe_entry

    # Friction & slippage (approx 1 pt for Nifty, 2.5 pts for Sensex)
    friction_per_share = 1.0 if instrument == 'NIFTY' else 2.5

    net_points = (pnl_short_ce + pnl_short_pe + pnl_long_ce + pnl_long_pe) - friction_per_share
    net_pnl_inr = net_points * total_qty

    return {
        'date': trade_date,
        'instrument': instrument,
        'mode': mode,
        'weekday': trade_date.strftime('%a'),
        'atm_strike': short_ce_strike,
        'ce_entry': round(short_ce_entry, 2),
        'ce_exit': round(short_ce_exit_price, 2),
        'ce_sl_hit': short_ce_sl_hit,
        'pe_entry': round(short_pe_entry, 2),
        'pe_exit': round(short_pe_exit_price, 2),
        'pe_sl_hit': short_pe_sl_hit,
        'hedge_ce_strike': long_ce_strike,
        'hedge_ce_cost': round(long_ce_entry, 2),
        'hedge_pe_strike': long_pe_strike,
        'hedge_pe_cost': round(long_pe_entry, 2),
        'net_points': round(net_points, 2),
        'net_pnl_inr': round(net_pnl_inr, 2),
        'is_win': net_pnl_inr > 0
    }


# -----------------------------------------------------------------------------
# Main Backtest Runner & Analytics
# -----------------------------------------------------------------------------
def run_backtest(lots=2, move_to_breakeven=False, df_nifty=None, df_sensex=None, vix_map=None):
    if df_nifty is None:
        df_nifty, df_sensex, vix_map = fetch_market_data()

    nifty_days = {d: g for d, g in df_nifty.groupby('Date')}
    sensex_days = {d: g for d, g in df_sensex.groupby('Date')}
    all_dates = sorted(list(set(list(nifty_days.keys()) + list(sensex_days.keys()))))

    results = []

    for d in all_dates:
        wd = d.weekday()
        # Monday(0), Tuesday(1), Friday(4) -> NIFTY
        # Wednesday(2), Thursday(3) -> SENSEX
        if wd in (0, 1, 4) and d in nifty_days:
            inst = 'NIFTY'
            mode = '1DTE' if wd in (0, 4) else '0DTE'
            g = nifty_days[d]
        elif wd in (2, 3) and d in sensex_days:
            inst = 'SENSEX'
            mode = '1DTE' if wd == 2 else '0DTE'
            g = sensex_days[d]
        else:
            continue

        daily_iv = vix_map.get(d, 0.13 if inst == 'NIFTY' else 0.125)
        trade_res = simulate_day(g, inst, mode, daily_iv, lots=lots, move_to_breakeven=move_to_breakeven)
        if trade_res:
            results.append(trade_res)

    res_df = pd.DataFrame(results)

    title_suffix = "WITH BREAKEVEN TRAILING" if move_to_breakeven else "OPTION A: ORIGINAL 30% SL"
    print("\n=================================================================")
    print(f"  BACKTEST RESULTS ({lots} LOTS) — {title_suffix}")
    print("=================================================================")

    if res_df.empty:
        print("No trades generated.")
        return

    total_trades = len(res_df)
    wins = res_df['is_win'].sum()
    losses = total_trades - wins
    win_rate = (wins / total_trades) * 100.0

    total_pnl = res_df['net_pnl_inr'].sum()
    avg_pnl = res_df['net_pnl_inr'].mean()

    gross_profit = res_df[res_df['net_pnl_inr'] > 0]['net_pnl_inr'].sum()
    gross_loss = abs(res_df[res_df['net_pnl_inr'] < 0]['net_pnl_inr'].sum())
    profit_factor = (gross_profit / gross_loss) if gross_loss > 0 else 999.0

    # Max Drawdown
    res_df['cum_pnl'] = res_df['net_pnl_inr'].cumsum()
    res_df['cum_max'] = res_df['cum_pnl'].cummax()
    res_df['drawdown'] = res_df['cum_max'] - res_df['cum_pnl']
    max_drawdown = res_df['drawdown'].max()

    print(f"Total Trading Days:     {total_trades}")
    print(f"Winning Trades:         {wins} ({win_rate:.1f}%)")
    print(f"Losing Trades:          {losses} ({100 - win_rate:.1f}%)")
    print(f"Profit Factor:          {profit_factor:.2f}")
    print(f"Total Realized P&L:     INR {total_pnl:,.2f}")
    print(f"Average P&L per Trade:  INR {avg_pnl:,.2f}")
    print(f"Max Drawdown:           INR {max_drawdown:,.2f}")
    print("-----------------------------------------------------------------")

    # Breakdown by instrument
    print("\nBreakdown by Index:")
    for inst, ig in res_df.groupby('instrument'):
        inst_pnl = ig['net_pnl_inr'].sum()
        inst_wr = (ig['is_win'].sum() / len(ig)) * 100
        print(f"  • {inst:<8}: Trades: {len(ig):2d} | Win Rate: {inst_wr:.1f}% | Net P&L: INR {inst_pnl:,.2f}")

    # Breakdown by DTE
    print("\nBreakdown by DTE & Day:")
    for key, dg in res_df.groupby(['instrument', 'weekday', 'mode']):
        d_pnl = dg['net_pnl_inr'].sum()
        d_wr = (dg['is_win'].sum() / len(dg)) * 100
        print(f"  • {key[0]} {key[1]} ({key[2]}): Trades: {len(dg):2d} | Win Rate: {d_wr:.1f}% | Net P&L: INR {d_pnl:,.2f}")

    print("\n=================================================================")
    print("                     LAST 15 TRADES LOG                          ")
    print("=================================================================")
    cols = ['date', 'weekday', 'instrument', 'mode', 'atm_strike', 'ce_entry', 'ce_exit', 'ce_sl_hit', 'pe_entry', 'pe_exit', 'pe_sl_hit', 'net_pnl_inr']
    print(res_df[cols].tail(15).to_string(index=False))
    print("=================================================================\n")
    return res_df

if __name__ == '__main__':
    lots = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    df_nifty, df_sensex, vix_map = fetch_market_data()

    print("\n>>> RUNNING BACKTEST: OPTION A (Keep 30% SL on surviving leg)...")
    df_opt_a = run_backtest(lots=lots, move_to_breakeven=False, df_nifty=df_nifty, df_sensex=df_sensex, vix_map=vix_map)

    print("\n>>> RUNNING BACKTEST: OPTION B (Trail surviving leg SL to BREAKEVEN)...")
    df_opt_b = run_backtest(lots=lots, move_to_breakeven=True, df_nifty=df_nifty, df_sensex=df_sensex, vix_map=vix_map)
