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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                log.warn("Failed to send Telegram alert: {}", e.getMessage());
                return Mono.empty();
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
                log.warn("Failed to send Telegram button message: {}", e.getMessage());
                return Mono.empty();
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

        if (signal.triggerPrice() != null) {
            sb.append("• Trigger/SL: `₹").append(signal.triggerPrice()).append("`\n");
        }
        if (signal.tag() != null) {
            sb.append("• Tag: _").append(signal.tag()).append("_\n");
        }

        sendAlert(sb.toString()).subscribe();
    }

    /**
     * Starts the reactive long-polling loop for receiving Telegram updates.
     */
    private void startPolling() {
        this.running.set(true);
        this.pollingDisposable = Schedulers.boundedElastic().schedulePeriodically(this::pollUpdates, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Polls the Telegram Bot API for new messages and callback queries.
     * Processes each update and advances the offset to avoid reprocessing.
     */
    private void pollUpdates() {
        if (!running.get() || !enabled) return;

        try {
            String url = String.format("/bot%s/getUpdates?offset=%d&timeout=10", botToken, updateOffset.get());
            String response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(12));

            if (response != null) {
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
            }
        } catch (Exception e) {
            log.debug("Telegram polling timeout or retry: {}", e.getMessage());
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
            sendAlert("""
                📋 *Available Commands:*
                • /status - System & broker connectivity overview
                • /pnl - Live MTM & Realized P&L breakdown
                • /strategies - Manage & toggle active strategies
                • /panic - Emergency L3 Global Panic Liquidation
                • /help - Show this command menu
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
        List<Strategy> list = strategyEngine.getRegisteredStrategies();
        if (list.isEmpty()) {
            sendAlert("No strategies currently registered.").subscribe();
            return;
        }

        List<List<Map<String, String>>> buttons = new ArrayList<>();
        for (Strategy s : list) {
            String btnText = (s.isEnabled() ? "⏸ Pause " : "▶️ Resume ") + s.getStrategyId();
            String cbData = (s.isEnabled() ? "PAUSE_STRAT:" : "RESUME_STRAT:") + s.getStrategyId();
            buttons.add(List.of(Map.of("text", btnText, "callback_data", cbData)));
        }

        sendMessageWithButtons("⚙️ *Strategy Controller*\nClick buttons below to pause or resume individual strategies:", buttons).subscribe();
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
