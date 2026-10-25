#!/usr/bin/env python3
import pytest, datetime, sys
from unittest.mock import patch, MagicMock

sys.path.insert(0, "scripts/forecast")
from digest import build_digest, send_telegram, TRADING, CHUNK_MAX

# fixture: minimal stats+verdict dict
S = lambda p_up, p_down, p_flat=None, p_volexp=0.3: {
    "p_up": p_up, "p_down": p_down,
    **({"p_flat": p_flat} if p_flat is not None else {}),
    "p_volexp": p_volexp,
}
V = lambda d, s: {"direction": d, "structure": s, "ce_zone": 3050.0, "pe_zone": 2890.0}

def _mk(sym, stats=None, verdict=None, error=None):
    d = {}
    if stats: d["stats"] = stats
    if verdict: d["verdict"] = verdict
    if error: d["error"] = error
    return {sym: d}

def test_trading_order_first():
    assets = _mk("RELIANCE", S(0.47, 0.41, 0.12), V("NEUTRAL", "SELL BOTH ~15D: CE >= 3050.1, PE <= 2890.3"))
    assets.update(_mk("TCS", S(0.71, 0.17), V("BULLISH", "SELL CALL BULL SPREAD short")))
    assets.update(_mk("INFOSYS", S(0.50, 0.40), V("NEUTRAL", "SELL BOTH ~15D: CE >= 1500.0, PE <= 1400.0")))
    txt = build_digest(assets, calibration={"as_of": "2026-10-20", "coverage_95_touch": 0.948, "direction_pass_lower_bound": 0.553})
    # RELIANCE before TCS
    assert txt.index("RELIANCE") < txt.index("TCS")
    # INFOSYS after trading block
    assert txt.index("INFOSYS") > txt.index("TCS")
    # NAKED-OK present
    assert "NAKED-OK (cal: 95-band coverage" in txt

def test_missing_symbol_unavailable():
    assets = _mk("RELIANCE", S(0.47, 0.41), V("NEUTRAL", "SELL BOTH ~15D: CE >= 3050.1, PE <= 2890.3"))
    assets.update(_mk("HDFCBANK", error="no data"))
    assets.update(_mk("ITC", stats=S(0.5,0.4)))
    txt = build_digest(assets)
    assert "HDFCBANK: UNAVAILABLE (no data)" in txt
    assert "ITC: UNAVAILABLE (no forecast)" in txt

def test_spread_only_when_no_cal():
    assets = _mk("RELIANCE", S(0.47, 0.41), V("NEUTRAL", "SELL BOTH ~15D: CE >= 3050.1, PE <= 2890.3"))
    txt = build_digest(assets, calibration=None)
    assert "SPREAD-ONLY (cal: no calibration)" in txt

def test_p_flat_absent_not_required():
    assets = _mk("RELIANCE", S(0.47, 0.41, p_flat=None), V("NEUTRAL", "SELL BOTH ~15D: CE >= 3050.1, PE <= 2890.3"))
    txt = build_digest(assets)
    assert "P(up)=47%" in txt and "P(down)=41%" in txt
    assert "P(flat)" not in txt

def test_send_telegram_ok():
    with patch.dict("os.environ", {"TELEGRAM_BOT_TOKEN": "t", "TELEGRAM_CHAT_ID": "c"}):
        with patch("digest.requests.post") as mp:
            r = MagicMock()
            r.status_code = 200
            r.json.return_value = {"ok": True}
            mp.return_value = r
            assert send_telegram("hello") is True

def test_send_telegram_conn_error():
    with patch.dict("os.environ", {"TELEGRAM_BOT_TOKEN": "t", "TELEGRAM_CHAT_ID": "c"}):
        with patch("digest.requests.post", side_effect=ConnectionError("dns")):
            assert send_telegram("hello") is False

def test_send_telegram_chunks():
    long = "a\n" * 5000  # > CHUNK_MAX
    with patch.dict("os.environ", {"TELEGRAM_BOT_TOKEN": "t", "TELEGRAM_CHAT_ID": "c"}):
        with patch("digest.requests.post") as mp:
            r = MagicMock()
            r.status_code = 200
            r.json.return_value = {"ok": True}
            mp.return_value = r
            ok = send_telegram(long)
            assert ok is True
            assert mp.call_count >= 2  # chunked