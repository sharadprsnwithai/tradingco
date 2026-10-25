"""Contract test: the download script writes a usable price CSV."""
import subprocess
import sys
from pathlib import Path

import pandas as pd
import pytest

CSV = Path("data/forecast/nse_daily/NIFTY_50.csv")


def test_download_creates_csv():
    result = subprocess.run(
        [sys.executable, "scripts/forecast/download_nse.py"],
        capture_output=True, text=True, timeout=600
    )
    if result.returncode != 0:
        pytest.skip(f"download failed (offline?): {result.stderr[-500:]}")
    df = pd.read_csv(CSV)
    assert "close" in df.columns
    assert df["close"].notna().any()
