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
import reactor.core.publisher.Sinks;
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

    private record QueuedAlert(String message, List<List<Map<String, String>>> buttons) {}
    private final Sinks.Many<QueuedAlert> alertQueue = Sinks.many().multicast().onBackpressureBuffer(1024);

    private final AtomicLong updateOffset = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Disposable pollingDisposable;
    private Disposable signalSub;
    private Disposable alertQueueSub;

    /**
     * Constructs the Telegram Bot Service with required dependencies.
     *
     * @param strategyEngine    strategy engine for signal subscription and control
     * @param oms               order manager service for trade operations
     * @param positionManager   position manager for P&L retrieval
     * @param killSwitch        kill switch for panic operations
     * @param marketDataHub     market data hub for broker status
     * @param ironFlyService    Iron Fly strategy service for options
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
            log.error("Telegram Bot is DISABLED — TELEGRAM_BOT_TOKEN and/or TELEGRAM_CHAT_ID are not set. "
                + "Trade/status alerts will NOT be delivered. Ensure .env is loaded at the runtime working "
                + "directory (or supply them as OS environment variables) and restart the bot.");
            return;
        }

        log.info("Telegram Bot ENABLED — alerts will be delivered to chat id '{}'", chatId);

        // 1. Start rate-limited alert queue processor with 429 backoff retry
        this.alertQueueSub = alertQueue.asFlux()
            .delayElements(Duration.ofMillis(300))
            .flatMap(alert -> executeSend(alert.message(), alert.buttons())
                .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
                    .filter(ex -> ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre
                        && (wcre.getStatusCode().value() == 429 || wcre.getStatusCode().is5xxServerError())))
                .onErrorResume(e -> {
                    log.error("Telegram message delivery failed after retries: {}", e.getMessage());
                    return Mono.empty();
                }), 1)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(null, err -> log.error("Telegram alert queue processor error: {}", err.getMessage()));

        // 2. Subscribe to StrategyEngine signals to push trade alerts
        this.signalSub = strategyEngine.getSignalStream()
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(this::sendSignalAlert, err -> log.error("Error in Telegram signal listener: {}", err.getMessage()));

        // 3. Start reactive long-polling for commands
        startPolling();
        sendAlert("🚀 *Trading Bot Online*\nEnvironment: Multi-Broker Reactive\nType /help or /status for controls.")
            .doOnError(e -> log.error("Initial Telegram 'Online' alert failed (check host egress to api.telegram.org): {}", e.getMessage()))
            .subscribe();
    }

    /**
     * Sends a markdown formatted message to the configured Telegram chat.
     */
    public Mono<Void> sendAlert(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return Mono.empty();
        }
        alertQueue.tryEmitNext(new QueuedAlert(message, null));
        return Mono.empty();
    }

    /**
     * Sends an interactive message with inline keyboard buttons.
     */
    public Mono<Void> sendMessageWithButtons(String message, List<List<Map<String, String>>> inlineKeyboard) {
        if (!enabled || message == null || message.isBlank()) {
            return Mono.empty();
        }
        alertQueue.tryEmitNext(new QueuedAlert(message, inlineKeyboard));
        return Mono.empty();
    }

    private Mono<Void> executeSend(String message, List<List<Map<String, String>>> inlineKeyboard) {
        String sanitized = sanitizeMarkdown(message);
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", sanitized);
        payload.put("parse_mode", "Markdown");
        if (inlineKeyboard != null) {
            payload.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
        }

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
                plainPayload.put("text", message != null ? message.replace("\uFFFD", "•") : "");
                if (inlineKeyboard != null) {
                    plainPayload.put("reply_markup", Map.of("inline_keyboard", inlineKeyboard));
                }
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

    private String sanitizeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("\uFFFD", "•");
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
            .subscribeOn(Schedulers.boundedElastic())
            .doFinally(signalType -> {
                if (running.get()) {
                    Schedulers.boundedElastic().schedule(this::pollLoop, 500, TimeUnit.MILLISECONDS);
                }
            })
            .subscribe();
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
        } else if (cmd.startsWith("/help") || cmd.startsWith("/start")) {
            handleHelpCommand();
        } else if (cmd.startsWith("/ironfly")) {
            handleIronFlyCommand(text.trim());
        }
    }

    /**
     * Handles inline button callback queries from Telegram interactive keyboards.
     *
     * @param queryNode JSON node containing the callback query data
     */
    public void handleCallbackQuery(JsonNode queryNode) {
        String data = queryNode.path("data").asText("");
        String callbackId = queryNode.path("id").asText("");

        // Acknowledge callback immediately to clear loading state
        webClient.post()
            .uri("/bot" + botToken + "/answerCallbackQuery")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(Map.of("callback_query_id", callbackId)))
            .retrieve()
            .bodyToMono(String.class)
            .subscribe();

        if ("CONFIRM_PANIC".equals(data)) {
            killSwitch.activateGlobalPanic("Triggered by Telegram Operator").subscribe();
            sendAlert("🚨 *GLOBAL PANIC ACTIVATED VIA TELEGRAM*\n• All active strategies PAUSED\n• All open orders CANCELLED\n• Market liquidation triggered across all accounts").subscribe();
        } else if (data.startsWith("PAUSE_")) {
            String rawId = data.substring("PAUSE_".length());
            String stratId = rawId.startsWith("STRAT:") ? rawId.substring("STRAT:".length()) : rawId;
            strategyEngine.pauseStrategy(stratId);
            sendAlert("⏸ *Strategy Paused:* `" + stratId + "`").subscribe();
        } else if (data.startsWith("RESUME_")) {
            String rawId = data.substring("RESUME_".length());
            String stratId = rawId.startsWith("STRAT:") ? rawId.substring("STRAT:".length()) : rawId;
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
        StringBuilder sb = new StringBuilder("⚙️ *STRATEGY DASHBOARD*\n\n");

        // 1. Registered strategies (VWAP, LVR, etc.)
        List<Strategy> list = strategyEngine.getRegisteredStrategies();
        if (!list.isEmpty()) {
            sb.append("*Intraday Strategies:*\n");
            for (Strategy s : list) {
                sb.append("• `").append(s.getStrategyId()).append("` ")
                  .append(s.isEnabled() ? "✅ RUNNING" : "⏸ PAUSED")
                  .append(" (").append(s.getAssignedAccountId()).append(")\n");
            }
            sb.append("\n");
        }

        // 2. Iron Fly positions
        if (ironFlyService != null) {
            Map<String, IronFlyPosition> ifPositions = ironFlyService.getActivePositions();
            if (!ifPositions.isEmpty()) {
                sb.append("*Iron Fly Positions:*\n");
                for (Map.Entry<String, IronFlyPosition> entry : ifPositions.entrySet()) {
                    IronFlyPosition pos = entry.getValue();
                    sb.append("• `").append(entry.getKey()).append("` [").append(pos.status()).append("] ")
                      .append("Credit: ₹").append(pos.getCurrentNetCredit())
                      .append(" | MTM: ₹").append(pos.getTotalMtm()).append("\n");
                }
                sb.append("\n");
            }
        }

        List<List<Map<String, String>>> buttons = new ArrayList<>();
        for (Strategy s : list) {
            String btnText = (s.isEnabled() ? "⏸ Pause " : "▶️ Resume ") + s.getStrategyId();
            String callbackData = (s.isEnabled() ? "PAUSE_" : "RESUME_") + s.getStrategyId();
            buttons.add(List.of(Map.of("text", btnText, "callback_data", callbackData)));
        }

        sendMessageWithButtons(sb.toString(), buttons).subscribe();
    }

    /**
     * Sends the command help manual to the user.
     */
    private void handleHelpCommand() {
        String help = """
            📖 *TRADING BOT CONTROL MANUAL*

            *Monitoring Commands:*
            • `/status` - Live broker feed status, failover state & strategies
            • `/pnl` - Real-time portfolio P&L (Intraday & Positional)
            • `/strategies` - Interactive dashboard to Pause/Resume strategies
            • `/help` - View this command manual

            *Iron Fly Commands:*
            • `/ironfly status` - View all active Iron Fly positions & breakevens
            • `/ironfly recommend` - Run entry scan & generate recommendations
            • `/ironfly evaluate` - Run daily 15:00 evaluation (TP/SL/adjustments)
            • `/ironfly book <SYMBOL> credit=<VAL> spot=<VAL> lot=<VAL>` - Book manual entry
              _Example:_ `/ironfly book RELIANCE credit=47.26 spot=1390 lot=250`

            *Emergency Commands:*
            • `/panic` - Cancel all orders & market-liquidate all open intraday positions
            """;
        sendAlert(help).subscribe();
    }

    /**
     * Handles /ironfly subcommands from Telegram chat.
     */
    private void handleIronFlyCommand(String fullText) {
        if (ironFlyService == null) {
            sendAlert("Iron Fly service is not available.").subscribe();
            return;
        }

        String[] parts = fullText.split("\\s+");
        if (parts.length < 2) {
            sendAlert("Usage: /ironfly <status|recommend|evaluate|book>").subscribe();
            return;
        }

        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "status" -> {
                Map<String, IronFlyPosition> positions = ironFlyService.getActivePositions();
                if (positions.isEmpty()) {
                    sendAlert("🦅 *Iron Fly:* No active positions.").subscribe();
                    return;
                }
                StringBuilder sb = new StringBuilder("🦅 *ACTIVE IRON FLY POSITIONS*\n\n");
                for (Map.Entry<String, IronFlyPosition> entry : positions.entrySet()) {
                    IronFlyPosition pos = entry.getValue();
                    sb.append(String.format("*%s* [%s]\n  Credit: ₹%.2f | MTM: ₹%.2f\n  BE: ₹%.2f / ₹%.2f\n  Adjustments: %d\n\n",
                        entry.getKey(), pos.status(),
                        pos.getCurrentNetCredit(), pos.getTotalMtm(),
                        pos.getUpperBreakeven(), pos.getLowerBreakeven(),
                        pos.getAdjustmentCount()));
                }
                sendAlert(sb.toString()).subscribe();
            }
            case "recommend" -> {
                sendAlert("🦅 Triggering Iron Fly entry recommendations scan...").subscribe();
                ironFlyService.sendRecommendations().subscribe();
            }
            case "evaluate" -> {
                sendAlert("🦅 Triggering daily Iron Fly position evaluation...").subscribe();
                ironFlyService.runDailyEvaluation().subscribe();
            }
            case "book" -> handleIronFlyBook(parts);
            default -> sendAlert("Unknown /ironfly subcommand. Use: status, recommend, evaluate, book").subscribe();
        }
    }

    /**
     * Handles /ironfly book RELIANCE credit=47.26 spot=1390 lot=250
     */
    private void handleIronFlyBook(String[] parts) {
        if (parts.length < 3) {
            sendAlert("Usage: /ironfly book <SYMBOL> credit=<VAL> spot=<VAL> lot=<VAL>\nExample: /ironfly book RELIANCE credit=47.26 spot=1390 lot=250").subscribe();
            return;
        }
        String underlying = parts[2].toUpperCase();
        double credit = 0;
        double spot = 0;
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
        if (alertQueueSub != null && !alertQueueSub.isDisposed()) {
            alertQueueSub.dispose();
        }
    }
}
