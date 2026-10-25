"""Walk-forward calibration: measured touch coverage and direction accuracy."""
import argparse
import json
import sys
from datetime import date
from math import sqrt
from pathlib import Path

if __package__:  # python -m scripts.forecast.calibrate: resolve flat sibling imports
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from data_loader import DATA_DIR, PriceDataError, load_price_frame  # noqa: E402
from forecast_core import FLAT_DEADBAND, forecast_asset, load_model  # noqa: E402

try:  # a real ChronosPipeline wants a tensor; unit tests run without torch
    import torch
except ImportError:  # pragma: no cover - exercised only in torch-less envs
    torch = None

OUT_PATH = Path("data/forecast/calibration.json")
MODEL_CACHE_DIR = Path("data/forecast/model_cache")


def wilson_lower_bound(passes: int, total: int, z: float = 1.96) -> float:
    """Lower bound of the Wilson score interval for `passes / total`."""
    if total < 0 or not 0 <= passes <= total:
        raise ValueError(f"invalid pass count {passes}/{total}")
    if total == 0:
        return 0.0
    p = passes / total
    z2 = z * z
    centre = p + z2 / (2 * total)
    margin = z * sqrt(p * (1 - p) / total + z2 / (4 * total * total))
    return max(0.0, (centre - margin) / (1 + z2 / total))


def run_walk_forward(frames: dict, model, months: int = 48, seed: int = 42) -> dict:
    """Score the last `months` months of every asset from trailing data only."""
    totals = {"covered95": 0, "covered80": 0, "obs": 0, "passes": 0, "directional": 0}
    per_asset = {}
    for asset_index, (symbol, frame) in enumerate(frames.items()):
        df = frame.sort_values("date", ignore_index=True)
        groups = list(df.groupby(df["date"].dt.to_period("M"), sort=True))[-months:]
        covered95 = covered80 = obs = passes = directional = skipped = 0
        for month_index, (period, rows) in enumerate(groups):
            trailing = df[df["date"] < period.start_time].reset_index(drop=True)
            if torch is not None:
                torch.manual_seed(seed + asset_index * 1000 + month_index)
            try:
                stats = forecast_asset(trailing, model)
            except PriceDataError:  # empty or zero-variance window: counted, not scored
                skipped += 1
                continue

            low, high = float(rows["close"].min()), float(rows["close"].max())
            obs += 1
            covered95 += low >= stats["touch95"][0] and high <= stats["touch95"][1]
            covered80 += low >= stats["touch80"][0] and high <= stats["touch80"][1]

            areturn = float(rows["close"].iloc[-1]) / stats["last_close"] - 1
            if abs(areturn) <= FLAT_DEADBAND:  # too flat to call a direction
                continue
            directional += 1
            passes += (stats["p_up"] > stats["p_down"]) == (areturn > 0)

        per_asset[symbol] = {
            "n_obs": obs,
            "coverage_95_touch": covered95 / obs if obs else 0.0,
            "coverage_80_touch": covered80 / obs if obs else 0.0,
            "direction_pass_rate": passes / directional if directional else 0.0,
            "skipped": skipped,
        }
        for key, value in (
            ("covered95", covered95),
            ("covered80", covered80),
            ("obs", obs),
            ("passes", passes),
            ("directional", directional),
        ):
            totals[key] += value
        print(f"{symbol}: {obs} obs, {skipped} skipped")

    n_obs = totals["obs"]
    return {
        "as_of": date.today().isoformat(),
        "n_obs": n_obs,
        "coverage_95_touch": totals["covered95"] / n_obs if n_obs else 0.0,
        "coverage_80_touch": totals["covered80"] / n_obs if n_obs else 0.0,
        "direction_pass_rate": (
            totals["passes"] / totals["directional"] if totals["directional"] else 0.0
        ),
        "direction_pass_lower_bound": wilson_lower_bound(
            totals["passes"], totals["directional"]
        ),
        "per_asset": per_asset,
    }


def main(argv=None) -> None:
    """Calibrate every stored symbol and write `data/forecast/calibration.json`."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--months", type=int, default=48)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    frames = {}
    for path in sorted(DATA_DIR.glob("*.csv")):
        try:
            frames[path.stem] = load_price_frame(path.stem)
        except PriceDataError as exc:
            print(f"{path.stem} skipped: {exc}")
    if not frames:
        raise SystemExit(f"no price data under {DATA_DIR}")

    model = load_model(MODEL_CACHE_DIR)
    report = run_walk_forward(frames, model, months=args.months, seed=args.seed)
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        f"calibration: {report['n_obs']} obs, "
        f"95-band {report['coverage_95_touch']:.1%}, "
        f"direction LCB {report['direction_pass_lower_bound']:.1%} -> {OUT_PATH}"
    )


if __name__ == "__main__":
    main()
