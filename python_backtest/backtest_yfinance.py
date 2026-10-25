import yfinance as yf
import pandas as pd
import numpy as np
from datetime import datetime, timedelta

def run_python_backtest():
    print("Downloading 3 months of Nifty 50 (^NSEI) data from Yahoo Finance...")
    end_date = datetime.now()
    start_date = end_date - timedelta(days=90)
    
    df = yf.download("^NSEI", start=start_date.strftime('%Y-%m-%d'), end=end_date.strftime('%Y-%m-%d'), interval="1d")
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.droplevel(1)
        
    print(f"Downloaded {len(df)} trading days.")
    
    # Simple Intraday Option Selling Simulation (Short Straddle + OTM Hedges)
    # Rules:
    # - Sell ATM Straddle at 10:00 AM (approximated using Open price)
    # - Buy OTM CE & PE hedges (±1000 points)
    # - 30% Stop loss on short legs
    # - Theta decay benefit at end of day
    
    total_pnl = 0.0
    trades = 0
    
    lot_size = 65  # Nifty lot size
    lots = 2
    qty = lot_size * lots
    
    for date, row in df.iterrows():
        open_price = row['Open']
        high = row['High']
        low = row['Low']
        close = row['Close']
        
        # Estimate ATM Straddle premium (~1% of spot)
        atm_straddle_premium = open_price * 0.008
        hedge_premium = open_price * 0.002
        
        # Net debit for buying hedges, net credit for selling straddle
        # Cost of hedges = 2 * hedge_premium
        # Credit from straddle = 2 * atm_straddle_premium
        net_credit = (atm_straddle_premium * 2 - hedge_premium * 2)
        
        # Check if high/low breached 30% stop loss (approx by daily range)
        range_pct = (high - low) / open_price
        
        day_pnl = 0.0
        if range_pct > 0.025: # Volatile day -> SL hit
            # Assume 30% loss on short legs
            sl_loss = atm_straddle_premium * 0.30 * 2
            day_pnl = (net_credit - sl_loss) * qty
        else: # Normal decay day -> full theta capture (e.g. 70% decay)
            decay_profit = atm_straddle_premium * 0.70 * 2
            day_pnl = (net_credit + decay_profit) * qty
            
        total_pnl += day_pnl
        trades += 1

    print("==================================================")
    print(" PYTHON YFINANCE 3-MONTH BACKTEST RESULTS (NIFTY) ")
    print("==================================================")
    print(f"Total Trading Days Traded: {trades}")
    print(f"Total Realized P&L: INR {total_pnl:,.2f}")
    print("==================================================")

if __name__ == "__main__":
    run_python_backtest()
