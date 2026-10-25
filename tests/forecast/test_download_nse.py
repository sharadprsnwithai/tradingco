"""Tests for the NSE downloader's per-symbol error isolation."""
import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "forecast"))

import download_nse  # noqa: E402


def _fake_frame():
    return pd.DataFrame(
        {
            "Open": [1.0, 2.0],
            "High": [1.0, 2.0],
            "Low": [1.0, 2.0],
            "Close": [1.0, 2.0],
            "Volume": [10, 20],
        },
        index=pd.DatetimeIndex(["2026-10-22", "2026-10-23"], name="Date"),
    )


def test_download_continues_after_bad_symbols(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(
        download_nse,
        "SYMBOLS",
        {"GOOD": "GOOD.NS", "BAD": "BAD.NS", "BROKEN": "BROKEN.NS", "ALSO_GOOD": "ALSO.NS"},
    )
    monkeypatch.setattr(download_nse, "OUT_DIR", tmp_path)

    def fake_download(ticker, **kwargs):
        if ticker == "BAD.NS":
            return pd.DataFrame()
        if ticker == "BROKEN.NS":
            raise ConnectionError("network down")
        return _fake_frame()

    monkeypatch.setattr(download_nse.yf, "download", fake_download)

    download_nse.download()

    out = capsys.readouterr().out
    assert "BAD skipped:" in out
    assert "BROKEN skipped:" in out
    assert "2 of 4 symbols" in out
    for symbol in ("GOOD", "ALSO_GOOD"):
        written = pd.read_csv(tmp_path / f"{symbol}.csv")
        assert list(written.columns) == [
            "date", "open", "high", "low", "close", "volume", "adjclose",
        ]
        assert written["close"].notna().all()
    assert not (tmp_path / "BAD.csv").exists()
    assert not (tmp_path / "BROKEN.csv").exists()
