"""
Kite Historical Data Strategy Test & Backtest Runner (Last 1 Month)
- Connects to live Kite REST API (via active session or credentials) or Yahoo Finance historical proxy.
- Tests the exact intraday option selling strategy logic (09:45 hedges, 10:00 ATM straddle, 30% SL + breakeven trailing, 15:00 square-off).
"""

import sys
import os
import math
from datetime import datetime, date, time, timedelta
import pandas as pd
import numpy as np
from scipy.stats import norm
import yfinance as yf

# Import core simulation engine from backtest_options_strategy
import backtest_options_strategy as bt

def run_kite_historical_test():
    print("=================================================================")
    print("      KITE HISTORICAL DATA STRATEGY TEST (LAST 30 DAYS)          ")
    print("=================================================================")

    # 1. Fetch last 30 days of 5-minute actual candle data
    df_nifty, df_sensex, vix_map = bt.fetch_market_data()
    
    # Filter for last 30 calendar days
    cutoff_date = date.today() - timedelta(days=30)
    
    df_nifty = df_nifty[df_nifty['Date'] >= cutoff_date].copy()
    df_sensex = df_sensex[df_sensex['Date'] >= cutoff_date].copy()

    print(f"Filtered for last 30 days: {len(df_nifty)} Nifty candles, {len(df_sensex)} Sensex candles.")

    # 2. Run Backtest with Breakeven Trailing (Option B)
    print("\nRunning strategy simulation on actual recent market data...")
    res_df = bt.run_backtest(lots=2, move_to_breakeven=True, df_nifty=df_nifty, df_sensex=df_sensex, vix_map=vix_map)

    print("\n=================================================================")
    print("               LAST 30 DAYS PERFORMANCE SUMMARY                  ")
    print("=================================================================")
    if res_df.empty:
        print("No completed trades in the last 30 days window.")
        return

    total_trades = len(res_df)
    wins = res_df['is_win'].sum()
    win_rate = (wins / total_trades) * 100.0
    total_pnl = res_df['net_pnl_inr'].sum()

    print(f"Total Trades:           {total_trades}")
    print(f"Win Rate:               {win_rate:.1f}%")
    print(f"Total Net P&L:          INR {total_pnl:,.2f}")
    print("-----------------------------------------------------------------")
    cols = ['date', 'weekday', 'instrument', 'mode', 'atm_strike', 'net_pnl_inr', 'ce_sl_hit', 'pe_sl_hit']
    print(res_df[cols].to_string(index=False))
    print("=================================================================\n")

if __name__ == '__main__':
    run_kite_historical_test()
