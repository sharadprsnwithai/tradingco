"""Tests for verdict rules and the naked-position calibration gate."""
import sys
from datetime import date, timedelta
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts" / "forecast"))

import rules  # noqa: E402
from rules import naked_ok, verdict  # noqa: E402

TOUCH = (98.0, 112.0)
VOL_SUFFIX = " | VOL↑: prefer spreads or widen strikes"


def stats(p_up, p_down, p_volexp=0.30, touch95=TOUCH, factor=1.0):
    return {
        "p_up": p_up,
        "p_down": p_down,
        "p_flat": round(1.0 - p_up - p_down, 10),
        "p_volexp": p_volexp,
        "touch95": touch95,
        "price_level_factor": factor,
    }


VERDICT_CASES = [
    # p_up, p_down, p_volexp, factor, direction, ce_zone, pe_zone, structure
    (
        0.47, 0.41, 0.30, 1.0, "NEUTRAL", 112.0, 98.0,
        "SELL BOTH ~15D: CE >= 112.0, PE <= 98.0",
    ),
    (
        0.47, 0.41, 0.60, 1.0, "NEUTRAL", 112.0, 98.0,
        "SELL BOTH ~15D: CE >= 112.0, PE <= 98.0" + VOL_SUFFIX,
    ),
    (
        0.71, 0.17, 0.30, 1.0, "BULLISH", 112.0, None,
        "SELL CALL BULL SPREAD (short leg near 112.0)",
    ),
    (
        0.71, 0.17, 0.80, 1.0, "BULLISH", 112.0, None,
        "SELL CALL BULL SPREAD (short leg near 112.0)" + VOL_SUFFIX,
    ),
    (
        0.17, 0.71, 0.30, 1.0, "BEARISH", None, 98.0,
        "SELL PUT BULL SPREAD (short leg near 98.0)",
    ),
    (
        0.17, 0.71, 0.80, 1.0, "BEARISH", None, 98.0,
        "SELL PUT BULL SPREAD (short leg near 98.0)" + VOL_SUFFIX,
    ),
    # strictly-above rule: exactly at the threshold is not directional
    (
        0.60, 0.28, 0.30, 1.0, "NEUTRAL", 112.0, 98.0,
        "SELL BOTH ~15D: CE >= 112.0, PE <= 98.0",
    ),
    (
        0.28, 0.60, 0.30, 1.0, "NEUTRAL", 112.0, 98.0,
        "SELL BOTH ~15D: CE >= 112.0, PE <= 98.0",
    ),
    (
        0.60, 0.28, 0.50, 1.0, "NEUTRAL", 112.0, 98.0,
        "SELL BOTH ~15D: CE >= 112.0, PE <= 98.0",
    ),
    # zones come from touch95, scaled by the price-level factor
    (
        0.47, 0.41, 0.30, 1.1, "NEUTRAL", 123.2, 107.8,
        "SELL BOTH ~15D: CE >= 123.2, PE <= 107.8",
    ),
    (
        0.71, 0.17, 0.30, 1.1, "BULLISH", 123.2, None,
        "SELL CALL BULL SPREAD (short leg near 123.2)",
    ),
    (
        0.17, 0.71, 0.30, 1.1, "BEARISH", None, 107.8,
        "SELL PUT BULL SPREAD (short leg near 107.8)",
    ),
]


@pytest.mark.parametrize(
    "p_up,p_down,p_volexp,factor,direction,ce_zone,pe_zone,structure", VERDICT_CASES
)
def test_verdict_mapping(p_up, p_down, p_volexp, factor, direction, ce_zone, pe_zone, structure):
    result = verdict(stats(p_up, p_down, p_volexp, factor=factor))
    assert result == {
        "direction": direction,
        "structure": structure,
        "ce_zone": ce_zone,
        "pe_zone": pe_zone,
    }


def test_verdict_keys_are_exactly_the_contract():
    assert set(verdict(stats(0.47, 0.41))) == {"direction", "structure", "ce_zone", "pe_zone"}


@pytest.mark.parametrize(
    "dropped", ["p_up", "p_down", "p_volexp", "touch95", "price_level_factor"]
)
def test_verdict_raises_on_missing_input_key(dropped):
    data = stats(0.47, 0.41)
    del data[dropped]
    with pytest.raises(KeyError):
        verdict(data)


def test_thresholds_live_only_in_module_constants():
    assert rules.DIR_THRESHOLD == 0.60
    assert rules.VOL_THRESHOLD == 0.50
    assert rules.STALE_DAYS == 31
    assert rules.NAKED_COV_95_MIN == 0.93
    assert rules.DIR_LCB_MIN == 0.55


def calibration(coverage=0.948, lcb=0.553, as_of=None):
    return {
        "coverage_95_touch": coverage,
        "direction_pass_lower_bound": lcb,
        "as_of": (as_of or date.today()).isoformat(),
    }


def test_naked_ok_without_calibration():
    assert naked_ok(None) == (False, "no calibration")


@pytest.mark.parametrize(
    "dropped", ["coverage_95_touch", "direction_pass_lower_bound", "as_of"]
)
def test_naked_ok_incomplete_calibration(dropped):
    data = calibration()
    del data[dropped]
    assert naked_ok(data) == (False, "no calibration")


def test_naked_ok_stale_calibration():
    as_of = date.today() - timedelta(days=40)
    assert naked_ok(calibration(as_of=as_of)) == (
        False,
        "calibration stale (age=40 days)",
    )


def test_naked_ok_stale_boundary():
    limit = date.today() - timedelta(days=rules.STALE_DAYS)
    assert naked_ok(calibration(as_of=limit))[0] is True
    assert naked_ok(calibration(as_of=limit - timedelta(days=1))) == (
        False,
        f"calibration stale (age={rules.STALE_DAYS + 1} days)",
    )


def test_naked_ok_passes_with_reason():
    assert naked_ok(calibration()) == (
        True,
        "95-band coverage 94.8%, dir LCB 55.3%",
    )


def test_naked_ok_gate_boundaries_pass():
    assert naked_ok(calibration(coverage=0.93, lcb=0.55))[0] is True


def test_naked_ok_low_coverage_rejected():
    ok, reason = naked_ok(calibration(coverage=0.90))
    assert ok is False
    assert reason not in ("no calibration",) and not reason.startswith("calibration stale")


def test_naked_ok_low_lcb_rejected():
    ok, reason = naked_ok(calibration(lcb=0.50))
    assert ok is False
    assert reason not in ("no calibration",) and not reason.startswith("calibration stale")
