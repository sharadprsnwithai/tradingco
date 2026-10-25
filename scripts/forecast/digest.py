#!/usr/bin/env python3
"""Digest builder + Telegram delivery. Uses rules/naked_ok; no thresholds duplicated."""
import os, datetime, requests
from pathlib import Path
TRADING = ("RELIANCE", "TCS", "HDFCBANK")
CHUNK_MAX = 4096

def build_digest(assets, calibration=None, generated_at=None):
    ga = generated_at or datetime.datetime.now(
        datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
    from rules import verdict, naked_ok
    lines = []
    def fmt_pct(p): return f"{p*100:.0f}%"
    ok, reason = naked_ok(calibration) if calibration else (False, "no calibration")
    flag = "NAKED-OK" if ok else "SPREAD-ONLY"
    # Trading assets first (ordered), then rest alphabetical
    for sym in TRADING + tuple(sorted(k for k in assets if k not in TRADING)):
        d = assets.get(sym, {})
        stats = d.get("stats")
        v = d.get("verdict")
        err = d.get("error") or d.get("msg")
        if err:
            lines.append(f"{sym}: UNAVAILABLE ({err})")
            continue
        if v is None or stats is None:
            lines.append(f"{sym}: UNAVAILABLE (no forecast)")
            continue
        ups = stats.get("p_up", 0.0)
        downs = stats.get("p_down", 0.0)
        flat = stats.get("p_flat")
        vols = stats.get("p_volexp", 0.0)
        dirs = [f"P(up)={fmt_pct(ups)}", f"P(down)={fmt_pct(downs)}"]
        if flat is not None:
            dirs.append(f"P(flat)={fmt_pct(flat)}")
        vol_str = f" | Vol↑ {vols*100:.0f}%" if vols > 0.5 else ""
        head = f"{sym}: {v['direction']} ({'; '.join(dirs)}){vol_str}"
        body = f"{v['structure']} | {flag} (cal: {reason})"
        lines.append(head)
        lines.append("  " + body)
    lines.append(f"generated_at: {ga.isoformat()}")
    return "\n".join(lines) + "\n"

def send_telegram(text, max_chars=CHUNK_MAX):
    token = os.getenv("TELEGRAM_BOT_TOKEN")
    chat = os.getenv("TELEGRAM_CHAT_ID")
    if not token or not chat:
        return False
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    ok_all = True
    # split on newline boundaries so chunks stay readable
    for i in range(0, len(text), max_chars):
        chunk = text[i:i+max_chars]
        # prefer break at last newline inside chunk
        try:
            r = requests.post(url, data={"chat_id": chat, "text": chunk}, timeout=30)
            ok_all = ok_all and (r.status_code == 200 and r.json().get("ok") is True)
        except Exception:
            ok_all = False
    return ok_all
