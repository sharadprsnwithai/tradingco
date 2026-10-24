# Kite & Telegram Automated Intraday Trading Bot

A production-grade algorithmic trading system built with Spring Boot 4.1 (Java 25), Gradle 9, and Reactor, featuring automated 0/1 DTE intraday option selling on **NIFTY** (Mon, Tue, Fri) and **SENSEX** (Wed, Thu) with exchange-level bracket orders, breakeven trailing, and real-time Telegram alerts via WebSockets (`KiteTickerClient`).

---

## 🚀 Prerequisites

- **Java 25 JDK & Gradle 9** (or use included `./gradlew wrapper`)
- **Docker & Docker Compose** (for containerized deployment)
- **Zerodha Kite Connect Account** (API Key, API Secret, User ID, Password, TOTP Secret)
- **Telegram Bot** (Token & Chat ID from @BotFather)

---

## ⚙️ Configuration (`.env`)

Copy `.env.example` to `.env` and populate your credentials:

```bash
cp .env.example .env
```

Ensure your `.env` contains:
```env
KITE_API_KEY=your_kite_api_key
KITE_API_SECRET=your_kite_api_secret
KITE_USER_ID=your_zerodha_user_id
KITE_PASSWORD=your_zerodha_password
KITE_TOTP_SECRET=your_base32_totp_seed
KITE_REDIRECT_URI=http://127.0.0.1/

TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id

STRATEGY_EXECUTION_MODE=PAPER
STRATEGY_SL_PERCENTAGE=30.0
STRATEGY_TRAIL_TO_BREAKEVEN=true
```

---

## 🐳 Docker & Docker Compose Commands

docker build

 docker build -t sharadprsn/trading-bot:latest .

### 1. Build and Start Container (Detached Mode)
```bash
docker compose up --build -d
```

### 2. View Live Application Logs
```bash
docker compose logs -f trading-bot
```

### 3. Check Container & Health Status
```bash
docker compose ps
```

### 4. Stop and Remove Containers
```bash
docker compose down
```

### 5. Restart Container
```bash
docker compose restart trading-bot
```

---

## 🛠️ Gradle Build & Test Commands

### Run Full Test Suite
```bash
./gradlew test
```

### Run Quality Gates (Spotless, PMD, Checkstyle, SpotBugs)
```bash
./gradlew check
```

### Build Executable Spring Boot JAR
```bash
./gradlew :broker:bootJar
```

---

## 🔍 Health & Monitoring Endpoints

- **Actuator Health Check:** [`http://localhost:8123/actuator/health`](http://localhost:8123/actuator/health)
- **Actuator Metrics:** [`http://localhost:8123/actuator/metrics`](http://localhost:8123/actuator/metrics)

---

## 📊 Monthly Range Forecast (forecast-api)

Run on last working day of month (~15:30 IST):
```bash
python scripts/forecast/download_nse.py
curl -X POST http://forecast-api:5599/forecast/run
```

Build / start:
```bash
docker build -t forecast-api forecast_api/
docker compose up forecast_api -d
```

Output: `data/forecast/latest.json` + Telegram alert via `NotificationPublisher`. Keeps 48 months history (`data/forecast/history/`).

**Build / deploy (Option A — Docker):**
```bash
docker build -t sharadprsn/forecast-api forecast_api/
docker push sharadprsn/forecast-api
docker compose up forecast-api -d
```

**Data download (run once per month):**
```bash
python scripts/forecast/download_nse.py
```
