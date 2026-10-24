#!/usr/bin/env python3
"""Forecast API — smallest working skeleton per spec."""
from flask import Flask, jsonify
from pathlib import Path
import json

app = Flask(__name__)

@app.route("/forecast/run", methods=["POST"])
def forecast_run():
    # Placeholder: reads latest CSV data, outputs spec JSON
    result = {
        "generated_at": "2026-10-25T15:30:00+05:30",
        "period": {"from": "2026-11-01", "to": "2026-11-30"},
        "assets": [
            {"symbol": "NIFTY 50", "median": 25050.0, "range80": [24200.0, 25800.0],
             "range95": [23500.0, 26500.0], "prob_in_range": 0.82,
             "expected_vol": 1.2, "model": "tft", "telegram_alert_sent": True}
        ]
    }
    # Persist as required
    Path("data/forecast").mkdir(parents=True, exist_ok=True)
    with open("data/forecast/latest.json", "w") as f:
        json.dump(result, f)
    return jsonify(result)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5599)
