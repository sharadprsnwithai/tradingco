"""Tests for walk-forward calibration: measured touch coverage + direction rate."""
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "forecast"))

import calibrate  # noqa: E402
from calibrate import run_walk_forward, wilson_lower_bound  # noqa: E402

HORIZON = 21
N_PATHS = 30
N_MONTHS = 60
DAYS_PER_MONTH = 21
GLOBAL_KEYS = {
    "as_of",
    "n_obs",
    "coverage_95_touch",
    "coverage_80_touch",
    "direction_pass_rate",
    "direction_pass_lower_bound",
    "per_asset",
}
PER_ASSET_KEYS = {
    "n_obs",
    "coverage_95_touch",
    "coverage_80_touch",
    "direction_pass_rate",
    "skipped",
}


class StubModel:
    """Always predicts up, with fixed envelopes around the trailing last close L.

    Three wide paths give touch95 = (0.86L, 1.10L); the other 27 give
    touch80 = (0.914L, 1.046L); 20 terminals sit at 1.02L and 10 at 0.98L.
    """

    def __init__(self):
        self.contexts = []

    def predict(self, context, prediction_length, num_samples):
        context = np.asarray(context, dtype=float)
        self.contexts.append(context)
        last = context[-1]
        paths = np.empty((N_PATHS, HORIZON))
        for i in range(N_PATHS):
            low, high = (0.86, 1.10) if i < 3 else (0.92, 1.04)
            terminal = (1.02 if i < 20 else 0.98) * last
            paths[i, 0] = low * last
            paths[i, 1] = high * last
            paths[i, 2:] = np.linspace(high * last, terminal, HORIZON - 2)
        return paths.reshape(1, N_PATHS, HORIZON)


def _specs(negative_every, overrides):
    """(final month return, intra-month dip, intra-month spike) per synthetic month."""
    specs = []
    for m in range(N_MONTHS):
        final = 0.98 if m % negative_every == negative_every - 1 else 1.02
        specs.append(overrides.get(m, (final, None, None)))
    return specs


# AAA: falls every 4th month; 2 unforeseen extremes and 1 inside-deadband month.
AAA = _specs(
    4,
    {
        17: (1.005, None, None),  # +0.5%: excluded from the direction denominator
        22: (1.02, 0.85, None),  # dip below both envelopes
        42: (1.02, 0.91, None),  # inside touch95, outside touch80
    },
)
# BBB: falls every 3rd month; 2 unforeseen extremes and 1 outside touch80 only.
BBB = _specs(
    3,
    {
        32: (0.98, None, 1.15),  # spike above both envelopes
        52: (1.02, None, 1.08),  # inside touch95, outside touch80
        56: (0.98, 0.85, None),  # dip below both envelopes
    },
)


def _frame(specs, start=100.0):
    """Daily closes: each calendar month is 21 rows chained off the previous close."""
    dates, closes = [], []
    prev = start
    for m, (final, dip, spike) in enumerate(specs):
        mults = np.linspace(1.0, final, DAYS_PER_MONTH)
        if dip is not None:
            mults[5] = dip
        if spike is not None:
            mults[7] = spike
        month = prev * mults
        dates.append(
            pd.date_range(
                pd.Timestamp("2020-01-01") + pd.DateOffset(months=m),
                periods=DAYS_PER_MONTH,
                freq="D",
            )
        )
        closes.append(month)
        prev = float(month[-1])
    closes = np.concatenate(closes)
    return pd.DataFrame(
        {
            "date": np.concatenate(dates),
            "open": closes,
            "high": closes * 1.001,
            "low": closes * 0.999,
            "close": closes,
            "volume": np.zeros(len(closes)),
        }
    )


@pytest.fixture()
def report():
    frames = {"AAA": _frame(AAA), "BBB": _frame(BBB)}
    return run_walk_forward(frames, StubModel(), months=48, seed=42)


def test_global_schema_and_observation_count(report):
    assert set(report) == GLOBAL_KEYS
    assert report["as_of"] == date.today().isoformat()
    assert report["n_obs"] == 48 * 2
    assert sum(a["n_obs"] for a in report["per_asset"].values()) == report["n_obs"]
    for stats in report["per_asset"].values():
        assert set(stats) == PER_ASSET_KEYS


def test_touch_coverage_matches_hand_counted_fractions(report):
    asset = report["per_asset"]
    # AAA: 48 months, 1 outside both envelopes, 1 outside only touch80.
    assert asset["AAA"]["n_obs"] == 48
    assert asset["AAA"]["coverage_95_touch"] == pytest.approx(47 / 48)
    assert asset["AAA"]["coverage_80_touch"] == pytest.approx(46 / 48)
    # BBB: 48 months, 2 outside both envelopes, 1 outside only touch80.
    assert asset["BBB"]["n_obs"] == 48
    assert asset["BBB"]["coverage_95_touch"] == pytest.approx(46 / 48)
    assert asset["BBB"]["coverage_80_touch"] == pytest.approx(45 / 48)

    assert report["coverage_95_touch"] == pytest.approx(93 / 96)
    assert report["coverage_80_touch"] == pytest.approx(91 / 96)


def test_direction_rate_excludes_deadband_months(report):
    asset = report["per_asset"]
    # AAA: 12 down months fail, 35 up months pass, 1 deadband month excluded.
    assert asset["AAA"]["direction_pass_rate"] == pytest.approx(35 / 47)
    # BBB: 16 down months fail, 32 up months pass, nothing excluded.
    assert asset["BBB"]["direction_pass_rate"] == pytest.approx(32 / 48)

    assert report["direction_pass_rate"] == pytest.approx(67 / 95)
    # Wilson lower bound of 67/95, computed by hand from the score interval.
    assert report["direction_pass_lower_bound"] == pytest.approx(0.607049, abs=5e-7)
    assert report["direction_pass_lower_bound"] <= report["direction_pass_rate"]


def test_wilson_lower_bound_known_values():
    assert wilson_lower_bound(0, 10) >= 0.0
    assert wilson_lower_bound(0, 10) < 0.1
    assert wilson_lower_bound(10, 10) >= 0.55
    assert wilson_lower_bound(10, 10) == pytest.approx(10 / (10 + 1.96**2))
    assert wilson_lower_bound(0, 0) == 0.0
    with pytest.raises(ValueError):
        wilson_lower_bound(3, 2)


def test_forecast_never_sees_rows_after_the_trailing_month():
    frames = {"AAA": _frame(AAA)}
    model = StubModel()
    run_walk_forward(frames, model, months=2, seed=42)
    df = frames["AAA"]
    months = df["date"].dt.to_period("M").unique()
    assert [len(c) for c in model.contexts] == [
        (N_MONTHS - 2) * DAYS_PER_MONTH,
        (N_MONTHS - 1) * DAYS_PER_MONTH,
    ]
    for context, period in zip(model.contexts, months[-2:]):
        np.testing.assert_allclose(context, df[df["date"] < period.start_time]["close"])


def test_degenerate_trailing_window_is_skipped_not_dropped():
    flat_first_month = [(1.0, None, None), (1.02, None, None), (1.02, None, None), (1.02, None, None)]
    report = run_walk_forward({"FLAT": _frame(flat_first_month)}, StubModel(), months=5)
    stats = report["per_asset"]["FLAT"]
    # month 0 has no trailing history, month 1's trailing window has zero variance
    assert stats["skipped"] == 2
    assert stats["n_obs"] == 2
    assert report["n_obs"] == 2
    assert stats["coverage_95_touch"] == pytest.approx(1.0)


def test_main_writes_calibration_json(tmp_path, monkeypatch):
    frames = {"AAA": _frame(AAA)}
    monkeypatch.setattr(calibrate, "DATA_DIR", tmp_path)
    monkeypatch.setattr(calibrate, "OUT_PATH", tmp_path / "calibration.json")
    monkeypatch.setattr(calibrate, "load_price_frame", lambda symbol: frames[symbol])
    monkeypatch.setattr(calibrate, "load_model", lambda cache_dir: StubModel())
    (tmp_path / "AAA.csv").write_text("date,open,high,low,close\n", encoding="utf-8")

    calibrate.main(["--months", "2"])

    data = json.loads((tmp_path / "calibration.json").read_text(encoding="utf-8"))
    assert set(data) == GLOBAL_KEYS
    assert data["as_of"] == date.today().isoformat()
    assert data["n_obs"] == 2
    assert set(data["per_asset"]) == {"AAA"}
