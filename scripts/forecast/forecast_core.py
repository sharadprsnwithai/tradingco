"""Chronos Monte Carlo core: per-asset terminal and touch statistics."""
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from data_loader import PriceDataError, get_adj_factor

MODEL_ID = "amazon/chronos-t5-small"
FLAT_DEADBAND = 0.01
VOL_LOOKBACK = 21

try:  # a real ChronosPipeline wants a tensor; unit tests run without torch
    import torch
except ImportError:  # pragma: no cover - exercised only in torch-less envs
    torch = None


class HfModelUnavailable(Exception):
    """Chronos weights are neither cached nor downloadable."""


def load_model(cache_dir: Path) -> Any:
    """Load the pinned Chronos pipeline, using cached weights when present."""
    from chronos import ChronosPipeline  # heavy optional dependency

    cache_dir = Path(cache_dir)
    try:
        return ChronosPipeline.from_pretrained(
            MODEL_ID, cache_dir=cache_dir, local_files_only=True
        )
    except Exception:
        pass  # nothing usable in the cache: fetch once
    try:
        return ChronosPipeline.from_pretrained(MODEL_ID, cache_dir=cache_dir)
    except Exception as exc:
        raise HfModelUnavailable(f"cannot load {MODEL_ID} into {cache_dir}") from exc


def forecast_asset(
    df: pd.DataFrame, model: Any, horizon: int = 21, n_paths: int = 30
) -> dict:
    """Sample paths once and reduce them to terminal, touch and probability stats."""
    close = df["close"].astype(float)
    if close.nunique() < 2:
        raise PriceDataError("close series has zero variance")

    context = close.to_numpy()
    if torch is not None:
        context = torch.as_tensor(context, dtype=torch.float32)
    preds = model.predict(context, prediction_length=horizon, num_samples=n_paths)
    paths = np.asarray(preds, dtype=float).reshape(n_paths, horizon)

    terminal = paths[:, -1]
    last_close = float(close.iloc[-1])

    ret = terminal / last_close - 1
    p_up = float((ret > FLAT_DEADBAND).mean())
    p_down = float((ret < -FLAT_DEADBAND).mean())

    # per-path std of the step returns, compared with daily close returns
    path_std = (paths[:, 1:] / paths[:, :-1] - 1).std(axis=1, ddof=1)
    hist_std = float(close.pct_change().tail(VOL_LOOKBACK).std(ddof=1))
    if hist_std == 0:
        p_volexp = 1.0 if (path_std > 0).any() else 0.0
    else:
        p_volexp = float((path_std > hist_std).mean())

    return {
        "median": float(np.median(terminal)),
        "term80": (
            float(np.quantile(terminal, 0.10)),
            float(np.quantile(terminal, 0.90)),
        ),
        "term95": (
            float(np.quantile(terminal, 0.05)),
            float(np.quantile(terminal, 0.95)),
        ),
        "touch95": (
            float(np.quantile(paths.min(axis=1), 0.05)),
            float(np.quantile(paths.max(axis=1), 0.95)),
        ),
        "p_up": p_up,
        "p_down": p_down,
        "p_flat": 1.0 - p_up - p_down,
        "p_volexp": p_volexp,
        "last_close": last_close,
        "price_level_factor": float(get_adj_factor(df).iloc[-1]),
    }
