package com.tradingbot;

import com.tradingbot.adapter.kite.KiteBrokerAdapter;
import com.tradingbot.adapter.shoonya.ShoonyaBrokerAdapter;
import com.tradingbot.instrument.InstrumentSyncService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.strategy.StrategyEngine;
import com.tradingbot.telegram.TelegramBotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@SpringBootApplication
@EnableScheduling
public class TradingBotApplication {

    private static final Logger log = LoggerFactory.getLogger(TradingBotApplication.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Entry point for the Trading Bot application.
     * Loads environment variables from the {@code .env} file and boots the Spring application context.
     *
     * @param args command-line arguments passed to the Spring application
     */
    public static void main(String[] args) {
        loadDotEnv();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook triggered — checkpointing SQLite WAL...");
            try {
                String dbPath = System.getProperty("bot.db.path", "data/trading_state.db");
                java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                try (java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                conn.close();
                log.info("SQLite WAL checkpoint completed");
            } catch (Exception e) {
                log.warn("WAL checkpoint on shutdown failed: {}", e.getMessage());
            }
        }));
        SpringApplication.run(TradingBotApplication.class, args);
    }

    /**
     * Creates an {@link ApplicationRunner} that executes at startup to verify connectivity
     * with all configured brokers, rehydrate open positions, and send a Telegram status notification.
     * <p>
     * The runner authenticates with Kite and Shoonya, fetches their current positions,
     * counts rehydrated intraday and positional positions, and dispatches a startup
     * or crash-recovery message to Telegram.
     *
     * @param kiteAdapter      the Kite broker adapter used for authentication and position retrieval
     * @param shoonyaAdapter   the Shoonya broker adapter used for authentication and position retrieval
     * @param positionManager  the service managing local intraday and positional position state
     * @param oms              the order manager service providing paper/live trading mode information
     * @param marketDataHub    the market data hub providing the active feed source broker identifier
     * @param strategyEngine   the strategy engine used to count registered trading strategies
     * @param telegramBot      the Telegram bot service used to send the startup notification
     * @return an {@link ApplicationRunner} bean that performs the startup verification sequence
     */
    @Bean
    public ApplicationRunner startupMultiBrokerChecker(
        KiteBrokerAdapter kiteAdapter,
        ShoonyaBrokerAdapter shoonyaAdapter,
        PositionManagerService positionManager,
        OrderManagerService oms,
        MarketDataHub marketDataHub,
        StrategyEngine strategyEngine,
        TelegramBotService telegramBot,
        InstrumentSyncService instrumentSyncService
    ) {
        return args -> {
            log.info("==================================================================");
            log.info("  MULTI-BROKER TRADING BOT STARTUP - VERIFYING LIVE BROKERS      ");
            log.info("==================================================================");

            // 1. Verify Kite (auth -> instrument master sync -> positions)
            kiteAdapter.authenticate()
                .then(Mono.defer(instrumentSyncService::syncFromKite))
                .doOnNext(count -> log.info(">>> KITE INSTRUMENTS SYNCED (Total: {}) <<<", count))
                .then(Mono.defer(kiteAdapter::getPositions))
                .doOnNext(positions -> {
                    log.info(">>> KITE POSITIONS FETCHED (Total: {}) <<<", positions.size());
                    for (var pos : positions) {
                        log.info("  [KITE POS] Symbol: {} | Book: {} | NetQty: {} | M2M PnL: ₹{} | BuyAvg: ₹{} | SellAvg: ₹{}",
                            pos.symbol(), pos.bookType(), pos.netQuantity(), pos.mtmPnl(), pos.buyAveragePrice(), pos.sellAveragePrice());
                    }
                })
                .doOnError(ex -> log.error("Failed to connect or fetch positions from Kite: {}", ex.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .block();

            // 2. Verify Shoonya (skip if disabled)
            if (shoonyaAdapter.isEnabled()) {
                shoonyaAdapter.authenticate()
                    .then(Mono.defer(shoonyaAdapter::getPositions))
                    .doOnNext(positions -> {
                        log.info(">>> SHOONYA POSITIONS FETCHED (Total: {}) <<<", positions.size());
                        for (var pos : positions) {
                            log.info("  [SHOONYA POS] Symbol: {} | Book: {} | NetQty: {} | M2M PnL: ₹{} | BuyAvg: ₹{} | SellAvg: ₹{}",
                                pos.symbol(), pos.bookType(), pos.netQuantity(), pos.mtmPnl(), pos.buyAveragePrice(), pos.sellAveragePrice());
                        }
                        log.info("==================================================================");
                    })
                    .doOnError(ex -> log.error("Failed to connect or fetch positions from Shoonya: {}", ex.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .block();
            } else {
                log.info("Shoonya adapter is disabled; skipping verification");
            }

            // 3. Send Telegram Startup / Crash Recovery Notification
            LocalDateTime nowIst = LocalDateTime.now(IST_ZONE);
            String timeStr = nowIst.format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
            int intradayCount = positionManager.getOpenIntradayPositions().size();
            int positionalCount = positionManager.getOpenPositionalPositions().size();
            int stratCount = strategyEngine.getRegisteredStrategies().size();

            boolean isMarketHours = (nowIst.getHour() == 9 && nowIst.getMinute() >= 15) || (nowIst.getHour() > 9 && nowIst.getHour() < 15) || (nowIst.getHour() == 15 && nowIst.getMinute() <= 30);

            StringBuilder sb = new StringBuilder();
            if (isMarketHours && (intradayCount > 0 || positionalCount > 0)) {
                sb.append("⚠️ *MID-DAY CRASH RECOVERY REHYDRATION*\n");
                sb.append("Bot restarted during active market hours. Local state successfully rehydrated from broker API truth.\n\n");
            } else {
                sb.append("🚀 *TRADING BOT BOOTED / ONLINE*\n\n");
            }

            sb.append("• Time (IST): `").append(timeStr).append("`\n")
              .append("• Feed Source: `").append(marketDataHub.getActiveBroker()).append("`\n")
              .append("• Execution Mode: `").append(oms.isPaperTrading() ? "PAPER TRADING (DRY RUN)" : "REAL MONEY (LIVE)").append("`\n")
              .append("• Active Strategies: `").append(stratCount).append("`\n")
              .append("• Intraday Positions Rehydrated: `").append(intradayCount).append("`\n")
              .append("• Positional Positions Rehydrated: `").append(positionalCount).append("`\n\n")
              .append("EOD Intraday Square-Off scheduled at *15:14 IST*.");

            telegramBot.sendAlert(sb.toString()).subscribe();
            log.info("TradingBot startup & rehydration initialization complete at {}", timeStr);
        };
    }

    /**
     * Wires the strategy signal stream into the Order Management System.
     * Every signal emitted by any strategy is risk-checked and routed to the
     * appropriate broker adapter for execution. {@code concatMap} preserves
     * signal ordering so an EXIT is never routed before its ENTRY.
     *
     * @param strategyEngine the strategy engine emitting trade signals
     * @param oms            the order manager service executing signals
     * @return an {@link ApplicationRunner} bean that activates the pipeline at startup
     */
    @Bean
    public ApplicationRunner signalOrderPipeline(StrategyEngine strategyEngine, OrderManagerService oms) {
        return args -> {
            strategyEngine.getSignalStream()
                .concatMap(oms::executeSignal)
                .onErrorContinue((err, sig) -> log.error("Signal execution pipeline error for {}: {}", sig, err.getMessage()))
                .subscribe(
                    order -> {},
                    err -> log.error("Signal execution pipeline terminated unexpectedly: {}", err.getMessage())
                );
            log.info("Signal -> OMS execution pipeline wired: strategy signals will be risk-checked and routed to brokers");
        };
    }

    /**
     * Loads configuration properties from a {@code .env} file in the application's working directory.
     * Each non-commented, non-empty line of the form {@code KEY=VALUE} is set as a system property,
     * provided the key is not already defined as a system property or environment variable.
     * Comment lines starting with {@code #} and blank lines are skipped.
     */
    private static void loadDotEnv() {
        File envFile = new File(".env");
        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                        int idx = line.indexOf('=');
                        String key = line.substring(0, idx).trim();
                        String value = line.substring(idx + 1).trim();
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, value);
                        }
                    }
                }
                log.info("Loaded configuration properties from .env file");
            } catch (Exception e) {
                log.warn("Could not load .env file: {}", e.getMessage());
            }
        }
    }
}
