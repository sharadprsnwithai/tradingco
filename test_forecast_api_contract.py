import requests, os, json

def test_forecast_api_output():
    # Fail-first: API not running; this verifies the contract exists
    # Once service runs, this becomes green
    assert False, "forecast_service.py skeleton created; needs Flask + yfinance integration for green"
