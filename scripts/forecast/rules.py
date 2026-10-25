"""Verdict rules and the naked-position calibration gate.

Money-critical: every threshold lives here and nowhere else.
"""
from datetime import date

DIR_THRESHOLD = 0.60  # p_up or p_down strictly above -> directional verdict
VOL_THRESHOLD = 0.50  # p_volexp strictly above -> vol warning suffix
STALE_DAYS = 31
NAKED_COV_95_MIN = 0.93
DIR_LCB_MIN = 0.55

_VOL_SUFFIX = " | VOL↑: prefer spreads or widen strikes"
_CAL_KEYS = ("coverage_95_touch", "direction_pass_lower_bound", "as_of")


def verdict(stats: dict) -> dict:
    """Turn forecast stats into a direction, a structure string and strike zones."""
    ce = round(stats["touch95"][1] * stats["price_level_factor"], 2)
    pe = round(stats["touch95"][0] * stats["price_level_factor"], 2)

    if stats["p_up"] > DIR_THRESHOLD:
        direction = "BULLISH"
        structure = f"SELL CALL BULL SPREAD (short leg near {ce:.1f})"
        ce_zone, pe_zone = ce, None
    elif stats["p_down"] > DIR_THRESHOLD:
        direction = "BEARISH"
        structure = f"SELL PUT BULL SPREAD (short leg near {pe:.1f})"
        ce_zone, pe_zone = None, pe
    else:
        direction = "NEUTRAL"
        structure = f"SELL BOTH ~15D: CE >= {ce:.1f}, PE <= {pe:.1f}"
        ce_zone, pe_zone = ce, pe

    if stats["p_volexp"] > VOL_THRESHOLD:
        structure += _VOL_SUFFIX

    return {
        "direction": direction,
        "structure": structure,
        "ce_zone": ce_zone,
        "pe_zone": pe_zone,
    }


def naked_ok(calibration: dict | None) -> tuple[bool, str]:
    """Allow naked legs only when fresh calibration clears both quality gates."""
    if not isinstance(calibration, dict) or not all(k in calibration for k in _CAL_KEYS):
        return False, "no calibration"

    age = (date.today() - date.fromisoformat(calibration["as_of"])).days
    if age > STALE_DAYS:
        return False, f"calibration stale (age={age} days)"

    cov = calibration["coverage_95_touch"]
    lcb = calibration["direction_pass_lower_bound"]
    if cov < NAKED_COV_95_MIN:
        return False, f"95-band coverage {cov:.1%} < {NAKED_COV_95_MIN:.0%}"
    if lcb < DIR_LCB_MIN:
        return False, f"dir LCB {lcb:.1%} < {DIR_LCB_MIN:.0%}"

    return True, f"95-band coverage {cov:.1%}, dir LCB {lcb:.1%}"
