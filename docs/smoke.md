# Real-Run Smoke Test Checklist

> **CRITICAL SECURITY NOTE:** Never commit real secrets or credentials to git. Never run this smoke test against production accounts in continuous integration (CI).

This checklist provides manual verification steps for deploying the trading bot in an authenticated environment.

## 1. Prerequisites

- Valid Zerodha Kite Connect API key and active trading account.
- Telegram Bot Token and authorized chat ID.
- Docker and Docker Compose installed locally.

## 2. Configuration

Copy the example environment file:

```bash
cp .env.example .env
```

Populate `.env` with actual credentials:
- `KITE_API_KEY`: Developer app API key
- `KITE_USER_ID`: 6-character Zerodha client ID
- `KITE_PASSWORD`: Account password
- `KITE_TOTP_SECRET`: Base32 secret seed for TOTP generation
- `TELEGRAM_BOT_TOKEN`: Token from @BotFather
- `TELEGRAM_CHAT_ID`: Destination user or channel chat ID

## 3. Starting the Container

Launch the application container:

```bash
docker compose up --build -d
```

Monitor container startup logs:

```bash
docker compose logs -f trading-bot
```

### Expected Startup Behaviors:
1. **Unattended Login:** Container logs should indicate successful session creation without logging passwords, tokens, or TOTP codes.
2. **Telegram Handshake:** A single `STARTUP` notification is immediately received on Telegram listing current open portfolio positions (or stating `no open positions`).
3. **No Duplicate Alerts:** Rebuild must silently adopt existing open orders without spamming `ORDER_SUBMITTED` notifications.

## 4. Health Verification

Verify container and actuator health endpoints:

```bash
curl -f http://127.0.0.1:8080/actuator/health
```

Expected JSON response:

```json
{
  "status": "UP",
  "components": {
    "tradingHealth": {
      "status": "UP",
      "details": {
        "kiteAuth": "UP",
        "notifications": "UP",
        "schedulers": "UP"
      }
    }
  }
}
```

Check Docker container health status:

```bash
docker compose ps
```

The container status must show `Up (healthy)`.

## 5. Teardown

Stop and remove the container:

```bash
docker compose down
```

Expected behavior: Telegram receives a final `SHUTDOWN` notification before JVM terminates.
