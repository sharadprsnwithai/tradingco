package com.tradingbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Position;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.KillSwitchService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.strategy.ironfly.IronFlyPosition;
import com.tradingbot.strategy.ironfly.IronFlyService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive Telegram Bot Service for Trade Notifications and Remote Bot Control.
 * Runs non-blocking reactive long-polling for commands and inline callback buttons.
 */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final StrategyEngine strategyEngine;
    private final OrderManagerService oms;
    private final PositionManagerService positionManager;
    private final KillSwitchService killSwitch;
    private final MarketDataHub marketDataHub;
    private final IronFlyService ironFlyService;

    private final String botToken;
    private final String chatId;
    private final boolean enabled;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private final AtomicLong updateOffset = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Disposable pollingDisposable;
    private Disposable signalSub;

    /**
     * Constructs the Telegram Bot Service with required dependencies.
     *
     * @param strategyEngine    strategy engine for signal subscription and control
     * @param oms               order manager service for trade operations
     * @param positionManager   position manager for P&L retrieval
     * @param killSwitch        kill switch for panic operations
     * @param marketDataHub     market data hub for broker status
     * @param botToken          Telegram bot API token
     * @param chatId            target Telegram chat ID for alerts
     * @param webClientBuilder  Spring WebClient builder for HTTP calls
     * @param objectMapper      Jackson ObjectMapper for JSON parsing
     */
    public TelegramBotService(
        StrategyEngine strategyEngine,
        OrderManagerService oms,
        PositionManagerService positionManager,
        KillSwitchService killSwitch,
        MarketDataHub marketDataHub,
        IronFlyService ironFlyService,
        @Value("${trading-bot.telegram.bot-token:}") String botToken,
        @Value("${trading-bot.telegram.chat-id:}") String chatId,
        WebClient.Builder webClientBuilder,
        ObjectMapper objectMapper
    ) {
        this.strategyEngine = strategyEngine;
        this.oms = oms;
        this.positionManager = positionManager;
        this.killSwitch = killSwitch;
        this.marketDataHub = marketDataHub;
        this.ironFlyService = ironFlyService;
        this.botToken = botToken;
        this.chatId = chatId;
        this.enabled = botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl("https://api.telegram.org").build();
    }

    /**
     * Initializes the Telegram bot by subscribing to strategy signals and starting long-polling.
     * Sends an online notification to the configured chat.
     */
    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Telegram Bot is disabled (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID not configured)");
            return;
        }

        // 1. Subscribe to StrategyEngine signals to push trade alerts
        this.signalSub = strategyEngine.getSignalStream()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(this::sendSignalAlert, err -> log.error("Error in Telegram signal listener: {}", err.getMessage()));

        // 2. Start reactive long-polling for commands
        startPolling();
        sendAlert("🚀 *Trading Bot Online*\nEnvironment: Multi-Broker Reactive\nType /help or /status for controls.");
    }

    /**
     * Sends a markdown formatted message to the configured Telegram chat.
     */
    public Mono<Void> sendAlert(String message) {
        if (!enabled) {
            log.debug("Telegram alert (disabled): {}", message);
            return Mono.empty();
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", message);
        payload.put("parse_mode", "Markdown");

        return webClient.post()
            .uri("/bot" + botToken + "/sendMessage")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(payload))
            .retrieve()
            .bodyToMono(String.class)
            .then()
            .onErrorResume(e -> {
                log.warn("Failed to send Telegram markdown alert ({}), retrying without markdown...", e.getMessage());
                Map<String, Object> plainPayload = new HashMap<>();
                plainPayload.put("chat_id", chatId);
                plainPayload.put("text", message);
                return webClient.post()
                    .uri("/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(plainPayload))
                    .retrieve()
                    .bodyToMono(String.class)
                    .then()
                    .onErrorResume(e2 -> {
                        log.error("Failed to send Telegram alert (plain fallback): {}", e2.getMessage());
                        return Mono.empty();
                    });
            });
    }

    /**
     * Sends a message with interactive inline buttons.
     */
    public Mono<Void> sendMessageWithButtons(String message, List<List<Map<String, String>>> inlineKeyboard) {
        if (!enabled) return Mono.empty();

        Map<String, Object> replyMarkup = Map.of("inline_keyboard", inlineKeyboard);
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", message);
        payload.put("parse_mode", "Markdown");
        payload.put("reply_markup", replyMarkup);

        return webClient.post()
            .uri("/bot" + botToken + "/sendMessage")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(payload))
            .retrieve()
            .bodyToMono(String.class)
            .then()
            .onErrorResume(e -> {
                log.warn("Failed to send Telegram button message with markdown ({}), retrying without markdown...", e.getMessage());
                Map<String, Object> plainPayload = new HashMap<>();
                plainPayload.put("chat_id", chatId);
                plainPayload.put("text", message);
                plainPayload.put("reply_markup", replyMarkup);
                return webClient.post()
                    .uri("/bot" + botToken + "/sendMessage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(plainPayload))
                    .retrieve()
                    .bodyToMono(String.class)
                    .then()
                    .onErrorResume(e2 -> {
                        log.error("Failed to send Telegram button message (plain fallback): {}", e2.getMessage());
                        return Mono.empty();
                    });
            });
    }

    /**
     * Formats and sends a trade signal alert to Telegram with strategy, symbol, and action details.
     *
     * @param signal the trade signal to format and send
     */
    private void sendSignalAlert(Signal signal) {
        if (signal == null) return;
        StringBuilder sb = new StringBuilder();

        switch (signal.signalType()) {
            case ENTRY_LONG -> sb.append("🟢 *SIGNAL: LONG ENTRY*\n");
            case ENTRY_SHORT -> sb.append("🔴 *SIGNAL: SHORT ENTRY*\n");
            case EXIT_PARTIAL_LONG, EXIT_PARTIAL_SHORT -> sb.append("💰 *PARTIAL PROFIT BOOKED (50%)*\n");
            case EXIT_LONG, EXIT_SHORT -> sb.append("⚠️ *TRADE EXITED*\n");
            case CANCEL -> sb.append("❌ *ORDER CANCELLED*\n");
        }

        sb.append("• Strategy: `").append(signal.strategyId()).append("`\n")
          .append("• Symbol: `").append(signal.symbol()).append("`\n")
          .append("• Action: *").append(signal.signalType()).append("*\n")
          .append("• Quantity: `").append(signal.quantity()).append("`\n")
          .append("• Price: `₹").append(signal.price()).append("`\n");

        if (signal.protectiveStopTrigger() != null) {
            sb.append("• Protective Stop: `₹").append(signal.protectiveStopTrigger()).append("`\n");
        } else if (signal.triggerPrice() != null) {
            sb.append("• Trigger/SL: `₹").append(signal.triggerPrice()).append("`\n");
        }
        if (signal.tag() != null) {
            sb.append("• Tag: `").append(signal.tag()).append("`\n");
        }

        sendAlert(sb.toString()).subscribe();
    }

    private void startPolling() {
        this.running.set(true);
        pollLoop();
    }

    /**
     * Executes sequential, non-overlapping long-polling against Telegram getUpdates API.
     */
    private void pollLoop() {
        if (!running.get() || !enabled) return;

        String url = String.format("/bot%s/getUpdates?offset=%d&timeout=10", botToken, updateOffset.get());
        this.pollingDisposable = webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(15))
            .doOnNext(this::processUpdatesResponse)
            .onErrorResume(e -> {
                log.debug("Telegram polling timeout or retry: {}", e.getMessage());
                return Mono.empty();
            })
            .delayElement(Duration.ofMillis(500))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                v -> {
                    if (running.get()) {
                        pollLoop();
                    }
                },
                err -> {
                    log.debug("Telegram poll loop error: {}", err.getMessage());
                    if (running.get()) {
                        Schedulers.boundedElastic().schedule(this::pollLoop, 2, TimeUnit.SECONDS);
                    }
                }
            );
    }

    /**
     * Parses the response from Telegram and dispatches updates.
     */
    private void processUpdatesResponse(String response) {
        if (response == null || response.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.path("ok").asBoolean(false)) {
                JsonNode result = root.path("result");
                if (result.isArray()) {
                    for (JsonNode update : result) {
                        long updateId = update.path("update_id").asLong();
                        updateOffset.set(updateId + 1);

                        if (update.has("message")) {
                            handleMessage(update.path("message"));
                        } else if (update.has("callback_query")) {
                            handleCallbackQuery(update.path("callback_query"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error processing Telegram updates: {}", e.getMessage());
        }
    }

    /**
     * Handles an incoming Telegram message and dispatches to the appropriate command handler.
     *
     * @param messageNode JSON node containing the message data
     */
    public void handleMessage(JsonNode messageNode) {
        String text = messageNode.path("text").asText("");
        if (text.isBlank()) return;

        String cmd = text.trim().toLowerCase();
        if (cmd.startsWith("/status")) {
            handleStatusCommand();
        } else if (cmd.startsWith("/pnl")) {
            handlePnlCommand();
        } else if (cmd.startsWith("/panic")) {
            handlePanicPrompt();
        } else if (cmd.startsWith("/strategies")) {
            handleStrategiesCommand();
        } else if (cmd.startsWith("/ironfly")) {
            handleIronFlyCommand(text.trim());
        } else if (cmd.startsWith("/help") || cmd.startsWith("/start")) {
            sendAlert("""
                \ud83d\udccb *Available Commands:*
                \u2022 /status - System & broker connectivity overview
                \u2022 /pnl - Live MTM & Realized P&L breakdown
                \u2022 /strategies - Manage & toggle active strategies
                \u2022 /ironfly - Iron Fly positions & breakevens
                \u2022 /ironfly book RELIANCE credit=47 spot=1390 lot=250 - Book manual entry
                \u2022 /panic - Emergency L3 Global Panic Liquidation
                \u2022 /help - Show this command menu
            """).subscribe();
        }
    }

    /**
     * Handles a callback query from inline keyboard buttons.
     * Acknowledges the query and dispatches actions such as panic, pause, or resume.
     *
     * @param callbackNode JSON node containing the callback query data
     */
    public void handleCallbackQuery(JsonNode callbackNode) {
        String data = callbackNode.path("data").asText("");
        String callbackId = callbackNode.path("id").asText("");

        // Acknowledge callback query
        webClient.post()
            .uri("/bot" + botToken + "/answerCallbackQuery")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(Map.of("callback_query_id", callbackId)))
            .retrieve()
            .bodyToMono(String.class)
            .onErrorResume(e -> {
                log.debug("Failed to answer callback query: {}", e.getMessage());
                return Mono.empty();
            })
            .subscribe();

        if ("CONFIRM_PANIC".equalsIgnoreCase(data)) {
            killSwitch.activateGlobalPanic("Triggered by Telegram Operator").subscribe();
            sendAlert("🚨 *GLOBAL PANIC ACTIVATED via Telegram* | All orders cancelled and intraday positions liquidated.").subscribe();
        } else if (data.startsWith("PAUSE_STRAT:")) {
            String stratId = data.substring("PAUSE_STRAT:".length());
            strategyEngine.pauseStrategy(stratId);
            sendAlert("⏸ *Strategy Paused:* `" + stratId + "`").subscribe();
        } else if (data.startsWith("RESUME_STRAT:")) {
            String stratId = data.substring("RESUME_STRAT:".length());
            strategyEngine.resumeStrategy(stratId);
            sendAlert("▶️ *Strategy Resumed:* `" + stratId + "`").subscribe();
        }
    }

    /**
     * Builds and sends a system status overview including broker connectivity and strategy states.
     */
    private void handleStatusCommand() {
        StringBuilder sb = new StringBuilder("📊 *SYSTEM STATUS OVERVIEW*\n\n");
        sb.append("• Active Broker Feed: `").append(marketDataHub.getActiveBroker()).append("`\n")
          .append("• Feed Failover: `").append(marketDataHub.isFailedOver() ? "YES (ON STANDBY)" : "NO (PRIMARY)") .append("`\n")
          .append("• Paper Trading: `").append(oms.isPaperTrading() ? "ENABLED (DRY RUN)" : "DISABLED (LIVE)") .append("`\n")
          .append("• Global Panic Active: `").append(killSwitch.isGlobalPanicActive()).append("`\n\n")
          .append("*Registered Strategies (").append(strategyEngine.getRegisteredStrategies().size()).append("):*\n");

        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            sb.append("• `").append(s.getStrategyId()).append("`: ")
              .append(s.isEnabled() ? "✅ RUNNING" : "⏸ PAUSED")
              .append(" (Account: `").append(s.getAssignedAccountId()).append("`)\n");
        }

        sendAlert(sb.toString()).subscribe();
    }

    /**
     * Builds and sends a portfolio P&L report with intraday and positional breakdown.
     */
    private void handlePnlCommand() {
        List<Position> intraday = positionManager.getOpenIntradayPositions();
        List<Position> positional = positionManager.getOpenPositionalPositions();

        BigDecimal intradayPnl = intraday.stream().map(Position::mtmPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal positionalPnl = positional.stream().map(Position::mtmPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPnl = intradayPnl.add(positionalPnl);

        StringBuilder sb = new StringBuilder("💰 *PORTFOLIO P&L REPORT*\n\n");
        sb.append("• *Total MTM P&L:* `₹").append(totalPnl).append("`\n")
          .append("• *Intraday Book (MIS):* `₹").append(intradayPnl).append("` (").append(intraday.size()).append(" open)\n")
          .append("• *Positional Book (NRML):* `₹").append(positionalPnl).append("` (").append(positional.size()).append(" open)\n\n");

        if (!intraday.isEmpty()) {
            sb.append("*Intraday Positions:*\n");
            for (Position p : intraday) {
                sb.append("• `").append(p.symbol()).append("` x ").append(p.netQuantity())
                  .append(" | MTM: `₹").append(p.mtmPnl()).append("`\n");
            }
        }

        sendAlert(sb.toString()).subscribe();
    }

    /**
     * Sends a confirmation prompt with inline button for global panic activation.
     */
    private void handlePanicPrompt() {
        List<List<Map<String, String>>> buttons = List.of(
            List.of(
                Map.of("text", "🚨 CONFIRM GLOBAL PANIC 🚨", "callback_data", "CONFIRM_PANIC")
            )
        );
        sendMessageWithButtons("⚠️ *EMERGENCY GLOBAL PANIC CONFIRMATION*\nThis will instantly cancel all open orders across all brokers and market-liquidate all open intraday positions.", buttons).subscribe();
    }

    /**
     * Sends an interactive strategy controller with pause/resume buttons for each registered strategy.
     */
    private void handleStrategiesCommand() {
        StringBuilder sb = new StringBuilder("\u2699\ufe0f *STRATEGY DASHBOARD*\n\n");

        // 1. Registered strategies (VWAP, LVR, etc.)
        List<Strategy> list = strategyEngine.getRegisteredStrategies();
        if (!list.isEmpty()) {
            sb.append("*Intraday Strategies:*\n");
            for (Strategy s : list) {
                sb.append("\u2022 `").append(s.getStrategyId()).append("` ")
                  .append(s.isEnabled() ? "\u2705 RUNNING" : "\u23f8 PAUSED")
                  .append(" (").append(s.getAssignedAccountId()).append(")\n");
            }
            sb.append("\n");
        }

        // 2. Iron Fly status
        Map<String, IronFlyPosition> ironFlyPositions = ironFlyService.getActivePositions();
        sb.append("*Iron Fly (Monthly Option Selling):*\n");
        if (ironFlyPositions.isEmpty()) {
            sb.append("  No active positions\n");
        } else {
            for (Map.Entry<String, IronFlyPosition> entry : ironFlyPositions.entrySet()) {
                IronFlyPosition p = entry.getValue();
                sb.append("\u2022 `").append(entry.getKey()).append("` [").append(p.status()).append("]\n");
                sb.append("  Credit: \u20b9").append(p.getCurrentNetCredit())
                  .append(" | MTM: \u20b9").append(p.getTotalMtm()).append("\n");
            }
        }
        sb.append("\n");

        // 3. Pause/Resume buttons for registered strategies
        List<List<Map<String, String>>> buttons = new ArrayList<>();
        for (Strategy s : list) {
            String btnText = (s.isEnabled() ? "\u23f8 Pause " : "\u25b6\ufe0f Resume ") + s.getStrategyId();
            String cbData = (s.isEnabled() ? "PAUSE_STRAT:" : "RESUME_STRAT:") + s.getStrategyId();
            buttons.add(List.of(Map.of("text", btnText, "callback_data", cbData)));
        }

        if (buttons.isEmpty()) {
            sendAlert(sb.toString()).subscribe();
        } else {
            sendMessageWithButtons(sb.toString(), buttons).subscribe();
        }
    }

    /**
     * Builds and displays Iron Fly position status with breakevens, decay, and adjustment history.
     */
    private void handleIronFlyCommand(String fullCmd) {
        String[] parts = fullCmd.split("\\s+");
        if (parts.length >= 2 && "book".equalsIgnoreCase(parts[1])) {
            handleIronFlyBook(parts);
            return;
        }
        Map<String, IronFlyPosition> positions = ironFlyService.getActivePositions();
        if (positions.isEmpty()) {
            sendAlert("No active Iron Fly positions.\n\nUsage: /ironfly book RELIANCE credit=47.26 spot=1390 lot=250").subscribe();
            return;
        }
        StringBuilder sb = new StringBuilder("\ud83e\udde9 *IRON FLY POSITIONS*\n\n");
        for (Map.Entry<String, IronFlyPosition> entry : positions.entrySet()) {
            IronFlyPosition p = entry.getValue();
            sb.append("*").append(entry.getKey()).append("* [").append(p.status()).append("]\n");
            sb.append("  ATM: `").append(p.getAtmStrike()).append("`\n");
            sb.append("  Credit: `\u20b9").append(p.getCurrentNetCredit()).append("`\n");
            sb.append("  MTM: `\u20b9").append(p.getTotalMtm()).append("`\n");
            sb.append("  BE: `\u20b9").append(p.getUpperBreakeven()).append(" / \u20b9").append(p.getLowerBreakeven()).append("`\n");
            sb.append("  Adjustments: `").append(p.getAdjustmentCount()).append("`\n\n");
        }
        sendAlert(sb.toString()).subscribe();
    }

    private void handleIronFlyBook(String[] parts) {
        if (parts.length < 5) {
            sendAlert("Usage: /ironfly book RELIANCE credit=47.26 spot=1390 lot=250").subscribe();
            return;
        }
        String underlying = parts[2].toUpperCase();
        double credit = 0, spot = 0;
        int lot = 250;
        for (int i = 3; i < parts.length; i++) {
            String[] kv = parts[i].split("=");
            if (kv.length == 2) {
                switch (kv[0].toLowerCase()) {
                    case "credit" -> credit = Double.parseDouble(kv[1]);
                    case "spot" -> spot = Double.parseDouble(kv[1]);
                    case "lot" -> lot = Integer.parseInt(kv[1]);
                }
            }
        }
        if (credit <= 0 || spot <= 0) {
            sendAlert("Invalid credit or spot. Usage: /ironfly book RELIANCE credit=47.26 spot=1390 lot=250").subscribe();
            return;
        }
        String result = ironFlyService.bookManualEntry(underlying, credit, spot, lot);
        sendAlert(result).subscribe();
    }

    /**
     * Cleans up resources by stopping polling and disposing signal subscription.
     */
    @PreDestroy
    public void cleanup() {
        this.running.set(false);
        if (pollingDisposable != null && !pollingDisposable.isDisposed()) {
            pollingDisposable.dispose();
        }
        if (signalSub != null && !signalSub.isDisposed()) {
            signalSub.dispose();
        }
    }
}
