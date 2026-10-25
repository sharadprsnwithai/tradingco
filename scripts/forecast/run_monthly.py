#!/usr/bin/env python3
"""Monthly orchestrator: predict -> verdict -> digest -> JSON + Telegram."""
import argparse, datetime, json, os, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from data_loader import load_price_frame, PriceDataError, DATA_DIR
from forecast_core import load_model, forecast_asset, FLAT_DEADBAND
from rules import verdict, naked_ok
from digest import build_digest, send_telegram, TRADING

SYMBOLS = ["NIFTY_50", "SENSEX", "BANK_NIFTY", "RELIANCE", "HDFC_BANK", "ICICI_BANK",
           "TCS", "BAJAJ_FINANCE", "INFOSYS", "AXIS_BANK", "NESTLE", "TITAN", "ITC"]
HORIZON = 21
N_PATHS = 30
STORAGE_DAYS = 1460  # 48 months
HISTORY_DIR = Path("data/forecast/history")
LATEST = Path("data/forecast/latest.json")
DIGEST_TXT = Path("data/forecast/latest_digest.txt")
CALIBRATION = Path("data/forecast/calibration.json")

def _ist_now():
    return datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5, minutes=30)))

def _prune_history():
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)
    cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=STORAGE_DAYS)
    for f in HISTORY_DIR.glob("*.json"):
        try:
            mtime = datetime.datetime.fromtimestamp(f.stat().st_mtime, datetime.timezone.utc)
            if mtime < cutoff:
                f.unlink()
        except OSError:
            pass

def _load_calibration():
    if not CALIBRATION.exists():
        return None
    try:
        with open(CALIBRATION) as fh:
            return json.load(fh)
    except (json.JSONDecodeError, OSError):
        return None

def _predict_asset(symbol, model, seed):
    try:
        df = load_price_frame(symbol)
    except PriceDataError as e:
        return None, f"no data: {e}"
    try:
        stats = forecast_asset(df, model, horizon=HORIZON, n_paths=N_PATHS)
    except Exception as e:
        return None, f"forecast failed: {e}"
    try:
        v = verdict(stats)
    except Exception as e:
        return None, f"verdict failed: {e}"
    return {"stats": stats, "verdict": v}, None

def run(months=48, seed=42, no_send=False):
    ga = _ist_now()
    calibration = _load_calibration()
    assets = {}
    try:
        model = load_model(Path("model_artifacts"))
    except Exception as e:
        model = None
        degraded = f"model unavailable: {e}"
    for i, sym in enumerate(SYMBOLS):
        if model is None:
            assets[sym] = {"error": degraded}
            continue
        res, err = _predict_asset(sym, model, seed + i * 1000)
        if res is None:
            assets[sym] = {"error": err}
        else:
            assets[sym] = res
    digest = build_digest(assets, calibration=calibration, generated_at=ga)
    # persist
    LATEST.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "generated_at": ga.isoformat(),
        "period": {"from": (ga + datetime.timedelta(days=1)).date().isoformat(),
                   "to": (ga + datetime.timedelta(days=HORIZON)).date().isoformat()},
        "assets": [{"symbol": s, "verdict": assets[s].get("verdict"),
                    "error": assets[s].get("error")} for s in SYMBOLS],
    }
    with open(LATEST, "w") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    DIGEST_TXT.write_text(digest, encoding="utf-8")
    # history + prune
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)
    (HISTORY_DIR / f"forecast_{ga.date().isoformat()}.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    _prune_history()
    # telegram (non-fatal)
    sent = False
    if not no_send:
        try:
            sent = send_telegram(digest)
        except Exception:
            sent = False
    return {"assets": assets, "digest": digest, "telegram_sent": sent,
            "calibration": calibration is not None}

def main(argv=None):
    ap = argparse.ArgumentParser(description="Monthly option-forecast digest")
    ap.add_argument("--months", type=int, default=48)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--no-send", action="store_true")
    args = ap.parse_args(argv)
    try:
        result = run(months=args.months, seed=args.seed, no_send=args.no_send)
    except Exception as e:
        print(f"FATAL: {e}", file=sys.stderr)
        return 1
    print(json.dumps({"telegram_sent": result["telegram_sent"],
                      "assets": len(result["assets"]), "calibration": result["calibration"]},
                     indent=2))
    return 0

if __name__ == "__main__":
    sys.exit(main())