"""Tests for the Chronos Monte Carlo forecast core."""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "forecast"))

from data_loader import PriceDataError  # noqa: E402
from forecast_core import FLAT_DEADBAND, forecast_asset  # noqa: E402

HORIZON = 21
N_PATHS = 30
KEYS = {
    "median",
    "term80",
    "term95",
    "touch95",
    "p_up",
    "p_down",
    "p_flat",
    "p_volexp",
    "last_close",
    "price_level_factor",
}
LAST_CLOSE = 110.0


class StubModel:
    """Stands in for ChronosPipeline: returns a fixed (1, n_paths, horizon) tensor."""

    def __init__(self, paths):
        self.paths = np.asarray(paths, dtype=float)
        self.calls = []

    def predict(self, context, prediction_length, num_samples):
        self.calls.append(
            {
                "context": np.asarray(context, dtype=float),
                "prediction_length": prediction_length,
                "num_samples": num_samples,
            }
        )
        return self.paths.reshape(1, *self.paths.shape)


def _frame(closes, adjclose=None):
    closes = np.asarray(closes, dtype=float)
    frame = pd.DataFrame(
        {
            "date": pd.date_range("2026-01-01", periods=len(closes), freq="D"),
            "open": closes,
            "high": closes * 1.01,
            "low": closes * 0.99,
            "close": closes,
            "volume": np.zeros(len(closes)),
        }
    )
    if adjclose is not None:
        frame["adjclose"] = np.asarray(adjclose, dtype=float)
    return frame


def _history(last=LAST_CLOSE, swing=0.02):
    """30 closes alternating +-swing, ending exactly at last."""
    closes = np.empty(30)
    closes[0::2] = last * (1 + swing)
    closes[1::2] = last * (1 - swing)
    closes[-1] = last
    return closes


def _sloped_paths():
    """Terminals spread from 105 to ~130 so up/down/flat are all non-empty."""
    i = np.arange(N_PATHS)[:, None]
    j = np.arange(HORIZON)[None, :]
    return 95.0 + 0.862 * i + 0.5 * j


def _straddle_paths():
    """Rising terminals, with alternating peak/trough excursions inside each path."""
    peak = np.linspace(0.0, 30.0, N_PATHS)[:, None]
    tri = 1 - np.abs(2 * np.arange(HORIZON) / (HORIZON - 1) - 1)
    sign = np.where(np.arange(N_PATHS)[:, None] % 2 == 0, 1.0, -1.0)
    return 100.0 + peak + sign * 0.5 * peak * tri[None, :]


def _terminal(paths):
    return paths[:, -1]


def test_stats_schema_and_probabilities():
    paths = _sloped_paths()
    model = StubModel(paths)
    stats = forecast_asset(_frame(_history()), model)

    assert set(stats) == KEYS
    assert stats["last_close"] == LAST_CLOSE
    assert stats["price_level_factor"] == 1.0
    assert stats["median"] == pytest.approx(np.median(_terminal(paths)))
    np.testing.assert_allclose(
        stats["term80"], [np.quantile(_terminal(paths), 0.10), np.quantile(_terminal(paths), 0.90)]
    )
    np.testing.assert_allclose(
        stats["term95"], [np.quantile(_terminal(paths), 0.05), np.quantile(_terminal(paths), 0.95)]
    )

    ret = _terminal(paths) / LAST_CLOSE - 1
    assert stats["p_up"] == pytest.approx((ret > FLAT_DEADBAND).mean())
    assert stats["p_down"] == pytest.approx((ret < -FLAT_DEADBAND).mean())
    assert stats["p_up"] > 0 and stats["p_down"] > 0 and stats["p_flat"] > 0
    assert stats["p_up"] + stats["p_down"] + stats["p_flat"] == pytest.approx(1.0, abs=1e-9)

    for key in ("median", "p_up", "p_down", "p_flat", "p_volexp", "last_close", "price_level_factor"):
        assert isinstance(stats[key], float)
    for key in ("term80", "term95", "touch95"):
        assert isinstance(stats[key], tuple) and len(stats[key]) == 2

    call = model.calls[0]
    assert call["prediction_length"] == HORIZON
    assert call["num_samples"] == N_PATHS
    np.testing.assert_allclose(call["context"], _history())


def test_touch_envelope_straddles_terminal_bands_and_term95_is_wider():
    paths = _straddle_paths()
    stats = forecast_asset(_frame(_history()), StubModel(paths))

    term80_lo, term80_hi = stats["term80"]
    term95_lo, term95_hi = stats["term95"]
    touch_lo, touch_hi = stats["touch95"]
    assert term95_lo < term80_lo and term95_hi > term80_hi
    assert touch_lo <= term95_lo
    assert touch_hi >= term95_hi
    np.testing.assert_allclose(
        [touch_lo, touch_hi],
        [np.quantile(paths.min(axis=1), 0.05), np.quantile(paths.max(axis=1), 0.95)],
    )


def test_p_volexp_zero_when_paths_are_flatter_than_history():
    paths = 110.0 + 0.001 * np.arange(N_PATHS)[:, None] + 0.0005 * np.arange(HORIZON)[None, :]
    stats = forecast_asset(_frame(_history()), StubModel(paths))
    assert stats["p_volexp"] == 0.0


def test_p_volexp_positive_when_paths_are_noisier_than_history():
    calm = np.full(30, LAST_CLOSE)
    calm[0::2] = LAST_CLOSE * 1.0001
    calm[1::2] = LAST_CLOSE * 0.9999
    calm[-1] = LAST_CLOSE
    noisy = LAST_CLOSE + 0.001 * np.arange(N_PATHS)[:, None] + 1.1 * (-1.0) ** np.arange(HORIZON)[None, :]
    stats = forecast_asset(_frame(calm), StubModel(noisy))
    assert stats["p_volexp"] > 0.0


def test_p_volexp_zero_history_variance_edge():
    frame = _frame(1e6 * 0.5 ** np.arange(30))  # every daily return is exactly -50%
    flat = np.full((N_PATHS, HORIZON), 1.0)
    assert forecast_asset(frame, StubModel(flat))["p_volexp"] == 0.0

    zig = 1.0 + 0.1 * (-1.0) ** np.arange(HORIZON)
    assert forecast_asset(frame, StubModel(np.tile(zig, (N_PATHS, 1))))["p_volexp"] == 1.0


def test_zero_variance_frame_raises():
    model = StubModel(_sloped_paths())
    with pytest.raises(PriceDataError):
        forecast_asset(_frame(np.full(30, 100.0)), model)
    assert model.calls == []


def test_price_level_factor_tracks_adj_factor():
    closes = _history()
    stats = forecast_asset(_frame(closes, adjclose=closes * 0.9), StubModel(_sloped_paths()))
    assert stats["price_level_factor"] == pytest.approx(0.9)


def test_forecast_does_not_touch_global_rng():
    np.random.seed(1234)
    before = np.random.get_state()
    stats = forecast_asset(_frame(_history()), StubModel(_sloped_paths()))
    after = np.random.get_state()
    assert before[0] == after[0]
    np.testing.assert_array_equal(before[1], after[1])
    assert before[2:] == after[2:]
    again = forecast_asset(_frame(_history()), StubModel(_sloped_paths()))
    assert stats == again
