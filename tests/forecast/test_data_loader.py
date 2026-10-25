"""Tests for the normalized price data loader."""
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "forecast"))

from data_loader import (  # noqa: E402
    PriceDataError,
    data_age_days,
    get_adj_factor,
    load_price_frame,
)


def _write_csv(data_dir, symbol, text):
    (data_dir / f"{symbol}.csv").write_text(text)


def test_load_price_frame_normalizes_columns(tmp_path):
    _write_csv(
        tmp_path,
        "TCS",
        "date,open,high,low,close,adjclose\n"
        "2026-10-22,4100,4150,4090,4120,4118\n"
        "2026-10-23,4120,4180,4110,4160,4158\n",
    )
    df = load_price_frame("TCS", data_dir=tmp_path)
    assert list(df.columns) == ["date", "open", "high", "low", "close", "volume"]
    assert pd.api.types.is_numeric_dtype(df["close"]) and not df["close"].isna().any()
    assert df["date"].is_monotonic_increasing


def test_data_age_days_missing_is_minus_one(tmp_path):
    assert data_age_days("NOPE", data_dir=tmp_path) == -1


def test_load_sorts_by_date_and_defaults_missing_volume(tmp_path):
    _write_csv(
        tmp_path,
        "TCS",
        "Date,Open,High,Low,Close,AdjClose\n"
        "2026-10-23,4120,4180,4110,4160,4158\n"
        "2026-10-21,4080,4110,4050,4100,4098\n",
    )
    df = load_price_frame("TCS", data_dir=tmp_path)
    assert df["close"].tolist() == [4100, 4160]
    assert df["volume"].tolist() == [0, 0]
    assert df["date"].is_monotonic_increasing


def test_missing_close_raises(tmp_path):
    _write_csv(tmp_path, "TCS", "date,open,high,low\n2026-10-23,1,2,3\n")
    with pytest.raises(PriceDataError):
        load_price_frame("TCS", data_dir=tmp_path)


def test_unparseable_close_raises(tmp_path):
    _write_csv(
        tmp_path, "TCS", "date,open,high,low,close\n2026-10-23,1,2,3,oops\n"
    )
    with pytest.raises(PriceDataError):
        load_price_frame("TCS", data_dir=tmp_path)


def test_empty_file_raises(tmp_path):
    _write_csv(tmp_path, "TCS", "")
    with pytest.raises(PriceDataError):
        load_price_frame("TCS", data_dir=tmp_path)


def test_missing_file_raises(tmp_path):
    with pytest.raises(PriceDataError):
        load_price_frame("NOPE", data_dir=tmp_path)


def test_get_adj_factor_is_adjclose_over_close():
    df = pd.DataFrame({"close": [100.0, 200.0], "adjclose": [95.0, 210.0]})
    assert get_adj_factor(df).tolist() == [0.95, 1.05]


def test_get_adj_factor_chains_after_load(tmp_path):
    _write_csv(
        tmp_path,
        "TCS",
        "date,open,high,low,close\n"
        "2026-10-22,4100,4150,4090,4120\n"
        "2026-10-23,4120,4180,4110,4160\n",
    )
    frame = load_price_frame("TCS", data_dir=tmp_path)
    assert get_adj_factor(frame).tolist() == [1.0, 1.0]


def test_data_age_days_counts_days_since_last_row(tmp_path):
    today = pd.Timestamp.today().normalize()
    last = today - pd.Timedelta(days=3)
    _write_csv(
        tmp_path,
        "TCS",
        "date,open,high,low,close\n"
        f"{today.date()},1,2,0.5,1.5\n"
        f"{last.date()},1,2,0.5,1.5\n",
    )
    assert data_age_days("TCS", data_dir=tmp_path) == 0
