"""Normalized price data access for the monthly forecast pipeline."""
from pathlib import Path

import pandas as pd

DATA_DIR = Path("data/forecast/nse_daily")
COLUMNS = ["date", "open", "high", "low", "close", "volume"]

REQUIRED = ["date", "open", "high", "low", "close"]


class PriceDataError(Exception):
    """Stored price data for a symbol is missing or unusable."""


def normalize_prices(df: pd.DataFrame) -> pd.DataFrame:
    """Lowercase columns, parse date/close, sort by date, default volume to 0."""
    if df.empty:
        raise PriceDataError("price frame is empty")
    df = df.rename(columns=str.lower)
    missing = [c for c in REQUIRED if c not in df]
    if missing:
        raise PriceDataError(f"missing columns: {missing}")
    df = df.assign(
        date=pd.to_datetime(df["date"], errors="coerce"),
        close=pd.to_numeric(df["close"], errors="coerce"),
    )
    if df["date"].isna().any() or df["close"].isna().any():
        raise PriceDataError("unparseable date or close")
    if "volume" not in df:
        df["volume"] = 0
    df["volume"] = pd.to_numeric(df["volume"], errors="coerce").fillna(0)
    return df[COLUMNS].sort_values("date", ignore_index=True)


def load_price_frame(symbol: str, data_dir: Path = DATA_DIR) -> pd.DataFrame:
    """Load one symbol's stored daily bars, normalized and sorted by date."""
    path = data_dir / f"{symbol}.csv"
    if not path.exists():
        raise PriceDataError(f"no price data for {symbol}: {path}")
    try:
        raw = pd.read_csv(path)
    except ValueError as exc:
        raise PriceDataError(f"unparseable price data for {symbol}: {exc}") from exc
    return normalize_prices(raw)


def get_adj_factor(df: pd.DataFrame) -> pd.Series:
    """Adjusted close divided by raw close, per row."""
    return df["adjclose"] / df["close"]


def data_age_days(symbol: str, data_dir: Path = DATA_DIR) -> int:
    """Days since the last stored bar, or -1 when no file exists."""
    if not (data_dir / f"{symbol}.csv").exists():
        return -1
    last = load_price_frame(symbol, data_dir)["date"].max()
    return (pd.Timestamp.today().normalize() - last).days
