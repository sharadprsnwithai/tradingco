import http.client
import json
import os
import ssl

bot_token = os.environ.get(
    "TELEGRAM_BOT_TOKEN", "8882260119:AAHdNQgOHSza6Foclxo-RyOAE6owQ6uiv7E"
)
chat_id = os.environ.get("TELEGRAM_CHAT_ID", "785151098")

ssl_ctx = ssl.create_default_context()


def https_get_json(host: str, path: str, headers: dict) -> dict:
    conn = http.client.HTTPSConnection(host, timeout=10, context=ssl_ctx)
    try:
        conn.request("GET", path, headers=headers)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8")
        return json.loads(raw)
    finally:
        conn.close()


def https_post_json(host: str, path: str, payload_dict: dict) -> dict:
    conn = http.client.HTTPSConnection(host, timeout=10, context=ssl_ctx)
    try:
        body = json.dumps(payload_dict)
        headers = {"Content-Type": "application/json"}
        conn.request("POST", path, body=body, headers=headers)
        resp = conn.getresponse()
        raw = resp.read().decode("utf-8")
        return json.loads(raw)
    finally:
        conn.close()


nse_headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Accept": "application/json",
    "Referer": "https://www.nseindia.com/",
}

gainers = []
losers = []

try:
    data_g = https_get_json(
        "www.nseindia.com", "/api/live-analysis-variations?index=gainers", nse_headers
    )
    gainers = data_g.get("FOSec", {}).get("data", [])
except Exception as e:
    print("Gainers fetch error:", e)

try:
    data_l = https_get_json(
        "www.nseindia.com", "/api/live-analysis-variations?index=loosers", nse_headers
    )
    losers = data_l.get("FOSec", {}).get("data", [])
except Exception as e:
    print("Losers fetch error:", e)

top_gainers = gainers[:10]
top_losers = losers[:10]

# Build Telegram Message
msg_lines = []
msg_lines.append("📊 *NSE F&O Stock Selection — Top 10 Gainers & Losers*\n")
msg_lines.append("🟢 *Top 10 F&O Gainers:*")
for i, gainer in enumerate(top_gainers, 1):
    sym = gainer.get("symbol", "")
    ltp = gainer.get("ltp", 0.0)
    pct = gainer.get("perChange", gainer.get("net_price", 0.0))
    msg_lines.append(f"{i}. `{sym}`: ₹{ltp:,.2f}  (+{pct:.2f}%)")

msg_lines.append("\n🔴 *Top 10 F&O Losers:*")
for i, loser in enumerate(top_losers, 1):
    sym = loser.get("symbol", "")
    ltp = loser.get("ltp", 0.0)
    pct = loser.get("perChange", loser.get("net_price", 0.0))
    msg_lines.append(f"{i}. `{sym}`: ₹{ltp:,.2f}  ({pct:.2f}%)")

message = "\n".join(msg_lines)

# Send to Telegram
payload = {
    "chat_id": chat_id,
    "text": message,
    "parse_mode": "Markdown",
}

try:
    res = https_post_json("api.telegram.org", f"/bot{bot_token}/sendMessage", payload)
    print(
        "Telegram Send Status: OK" if res.get("ok") else f"Telegram Send Status: {res}"
    )
except Exception as e:
    print("Telegram Send Failed:", e)

# Print Summary
print("=== TOP 10 GAINERS ===")
for i, gainer in enumerate(top_gainers, 1):
    print(
        f"{i}. {gainer.get('symbol')}: LTP Rs {gainer.get('ltp')} (+{gainer.get('perChange')}%)"
    )

print("\n=== TOP 10 LOSERS ===")
for i, loser in enumerate(top_losers, 1):
    print(
        f"{i}. {loser.get('symbol')}: LTP Rs {loser.get('ltp')} ({loser.get('perChange')}%)"
    )
