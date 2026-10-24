import subprocess, sys

def test_download_creates_csv():
    result = subprocess.run(
        [sys.executable, "scripts/forecast/download_nse.py"],
        capture_output=True, text=True, timeout=300
    )
    assert result.returncode == 0, f"Download failed: {result.stderr}"
    import os
    assert os.path.exists("data/forecast/nse_daily/NIFTY_50.csv")
