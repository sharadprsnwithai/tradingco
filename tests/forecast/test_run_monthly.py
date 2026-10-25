#!/usr/bin/env python3
"""Orchestrator tests — mocks all heavy deps; asserts flow + degradation, no network."""
import pytest, json, datetime, os
from pathlib import Path
from unittest.mock import patch, MagicMock

def test_orchestrator_wiring_and_json_contract(tmp_path, monkeypatch):
    from scripts.forecast import run_monthly as rm
    monkeypatch.setattr(rm, "LATEST", tmp_path / "latest.json")
    monkeypatch.setattr(rm, "DIGEST_TXT", tmp_path / "digest.txt")
    monkeypatch.setattr(rm, "HISTORY_DIR", tmp_path / "history")
    monkeypatch.setattr(rm, "CALIBRATION", tmp_path / "calibration.json")
    (tmp_path / "calibration.json").write_text(json.dumps({
        "as_of": "2026-10-20", "coverage_95_touch": 0.94,
        "direction_pass_lower_bound": 0.56}))
    monkeypatch.setattr(rm, "_ist_now",
        lambda: datetime.datetime(2026, 10, 25, 15, 30,
            tzinfo=datetime.timezone(datetime.timedelta(hours=5, minutes=30))))
    fake_stats = {"p_up":0.47,"p_down":0.41,"p_flat":0.12,"p_volexp":0.3,
                  "touch95":(2890.0,3050.0),"price_level_factor":1.0,
                  "last_close":3000.0,"median":3000.0,"term95":(2800.0,3200.0)}
    fake_verdict = {"direction":"NEUTRAL","structure":"SELL BOTH ~15D: CE >= 3050.1, PE <= 2890.3",
                    "ce_zone":3050.1,"pe_zone":2890.3}
    monkeypatch.setattr(rm, "load_price_frame", MagicMock(return_value=MagicMock()))
    monkeypatch.setattr(rm, "load_model", lambda d: MagicMock())  # model present
    monkeypatch.setattr(rm, "forecast_asset", lambda df, model, horizon, n_paths: fake_stats)
    monkeypatch.setattr(rm, "verdict", lambda s: fake_verdict)
    monkeypatch.setattr(rm, "naked_ok", lambda c: (True, "95-band coverage 94.0%, dir LCB 55.0%"))
    captured = {}
    monkeypatch.setattr(rm, "build_digest",
        lambda a, calibration=None, generated_at=None:
            (captured.update(a=a, c=calibration, g=generated_at) or "DUMMY DIGEST [NAKED]"))
    monkeypatch.setattr(rm, "send_telegram", lambda t: (captured.update(t=t), True)[1])
    res = rm.run(months=48, seed=42, no_send=False)
    assert res["telegram_sent"] is True
    assert len(res["assets"]) == len(rm.SYMBOLS)
    assert res["calibration"] is True
    latest = json.loads((tmp_path / "latest.json").read_text())
    assert set(latest) == {"generated_at", "period", "assets"}
    assert latest["assets"][0]["symbol"] == rm.SYMBOLS[0]
    assert "DUMMY DIGEST" in (tmp_path / "digest.txt").read_text()
    assert (tmp_path / "history").exists()

def test_orchestrator_degraded_when_model_missing(tmp_path, monkeypatch):
    from scripts.forecast import run_monthly as rm
    monkeypatch.setattr(rm, "LATEST", tmp_path / "latest.json")
    monkeypatch.setattr(rm, "DIGEST_TXT", tmp_path / "digest.txt")
    monkeypatch.setattr(rm, "HISTORY_DIR", tmp_path / "history")
    monkeypatch.setattr(rm, "CALIBRATION", tmp_path / "cal_not_there.json")  # missing
    monkeypatch.setattr(rm, "load_model", lambda d: (_ for _ in ()).throw(RuntimeError("no network")))
    monkeypatch.setattr(rm, "build_digest",
        lambda a, calibration=None, generated_at=None: "SPREAD-ONLY DIGEST")
    monkeypatch.setattr(rm, "send_telegram", lambda t: True)
    res = rm.run(no_send=True)
    assert all(v.get("error") for v in res["assets"].values())
    assert "SPREAD-ONLY" in res["digest"]