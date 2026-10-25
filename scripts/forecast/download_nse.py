#!/usr/bin/env python3
"""Download NSE daily data using yfinance. Offline-first; run monthly."""
import yfinance as yf
from pathlib import Path

from data_loader import normalize_prices

SYMBOLS = {
    "NIFTY_50": "^NSEI",
    "SENSEX": "^BSESN",
    "BANK_NIFTY": "^NSEBANK",
    "RELIANCE": "RELIANCE.NS",
    "HDFC_BANK": "HDFCBANK.NS",
    "ICICI_BANK": "ICICIBANK.NS",
    "TCS": "TCS.NS",
    "BAJAJ_FINANCE": "BAJFINANCE.NS",
    "INFOSYS": "INFY.NS",
    "AXIS_BANK": "AXISBANK.NS",
    "NESTLE": "NESTLEIND.NS",
    "TITAN": "TITAN.NS",
    "ITC": "ITC.NS",
}

OUT_DIR = Path("data/forecast/nse_daily")

def download():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, ticker in SYMBOLS.items():
        raw = yf.download(
            ticker,
            period="5y",
            interval="1d",
            auto_adjust=True,
            progress=False,
            multi_level_index=False,
        )
        df = normalize_prices(raw.reset_index())
        # yfinance auto_adjust already folds splits/dividends into Close.
        df["adjclose"] = df["close"]
        df.to_csv(OUT_DIR / f"{name}.csv", index=False)
        print(f"Downloaded {name}: {len(df)} rows")

if __name__ == "__main__":
    download()
