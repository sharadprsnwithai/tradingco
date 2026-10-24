#!/bin/bash
# Run monthly forecast cycle: download → forecast → alert
set -euo pipefail
python scripts/forecast/download_nse.py
curl -sfX POST http://forecast-api:5599/forecast/run || echo "API unavailable — forecast skipped; cached forecast remains"
