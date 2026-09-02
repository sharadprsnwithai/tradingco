package com.tradingbot.risk;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.CircularCandleBuffer;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.position.PositionManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-Trade Risk Management System (RMS).
 * Enforces strict 4-point guardrails, emergency states, and loss limits before orders reach OMS.
 */
@Service
public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);

    private final TradingDbService dbService;
    private final MarketDataHub marketDataHub;
    private final PositionManagerService positionManager;

    private final BigDecimal maxDailyLossPerStrategy;
    private final BigDecimal maxDailyLossGlobal;
    private final int maxOpenPositionsPerStrategy;
    private final int maxOpenPositionsGlobal;
    private final int maxOrderQuantity;
    private final BigDecimal maxOrderValue;
    private final double maxPriceDeviationPercent;

    private final Map<String, BigDecimal> dailyLossByStrategy = new ConcurrentHashMap<>();
    private volatile BigDecimal dailyLossGlobal = BigDecimal.ZERO;
    private final Map<String, AtomicInteger> openPositionsByStrategy = new ConcurrentHashMap<>();

    private final AtomicBoolean globalPanicActive = new AtomicBoolean(false);
    private final Set<String> frozenBrokers = ConcurrentHashMap.newKeySet();
    private final Set<String> pausedStrategies = ConcurrentHashMap.newKeySet();

    @Autowired
    public RiskManager(
        TradingDbService dbService,
        MarketDataHub marketDataHub,
        @Lazy @Autowired(required = false) PositionManagerService positionManager,
        @Value("${bot.risk.max-daily-loss-per-strategy:5000}") double maxDailyLossPerStrategy,
        @Value("${bot.risk.max-daily-loss-global:15000}") double maxDailyLossGlobal,
        @Value("${bot.risk.max-open-positions-per-strategy:3}") int maxOpenPositionsPerStrategy,
        @Value("${bot.risk.max-open-positions-global:10}") int maxOpenPositionsGlobal,
        @Value("${bot.risk.max-order-quantity:500}") int maxOrderQuantity,
        @Value("${bot.risk.max-order-value:200000}") double maxOrderValue,
        @Value("${bot.risk.max-price-deviation-percent:3.0}") double maxPriceDeviationPercent
    ) {
        this.dbService = dbService;
        this.marketDataHub = marketDataHub;
        this.positionManager = positionManager;
        this.maxDailyLossPerStrategy = BigDecimal.valueOf(maxDailyLossPerStrategy);
        this.maxDailyLossGlobal = BigDecimal.valueOf(maxDailyLossGlobal);
        this.maxOpenPositionsPerStrategy = maxOpenPositionsPerStrategy;
        this.maxOpenPositionsGlobal = maxOpenPositionsGlobal;
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxOrderValue = BigDecimal.valueOf(maxOrderValue);
        this.maxPriceDeviationPercent = maxPriceDeviationPercent;
    }

    public RiskManager(
        TradingDbService dbService,
        MarketDataHub marketDataHub,
        double maxDailyLossPerStrategy,
        double maxDailyLossGlobal,
        int maxOpenPositionsPerStrategy,
        int maxOpenPositionsGlobal,
        int maxOrderQuantity,
        double maxOrderValue,
        double maxPriceDeviationPercent
    ) {
        this(dbService, marketDataHub, null, maxDailyLossPerStrategy, maxDailyLossGlobal,
            maxOpenPositionsPerStrategy, maxOpenPositionsGlobal, maxOrderQuantity, maxOrderValue, maxPriceDeviationPercent);
    }

    /**
     * Pre-Trade Risk Check. Validates every inbound signal against all 4 guardrails and emergency states.
     */
    public Mono<RiskCheckResult> validateSignal(Signal signal) {
        if (signal == null) {
            return Mono.just(RiskCheckResult.reject("NULL_SIGNAL", "Signal cannot be null"));
        }

        // 1. Emergency Kill Switch Checks
        if (globalPanicActive.get()) {
            return Mono.just(RiskCheckResult.reject("GLOBAL_PANIC", "Trading rejected: Global Panic Kill Switch is active"));
        }

        if (signal.targetAccountId() != null && frozenBrokers.contains(signal.targetAccountId().toUpperCase())) {
            return Mono.just(RiskCheckResult.reject("BROKER_FROZEN", "Trading rejected: Broker account " + signal.targetAccountId() + " is frozen"));
        }

        if (signal.strategyId() != null && pausedStrategies.contains(signal.strategyId())) {
            return Mono.just(RiskCheckResult.reject("STRATEGY_PAUSED", "Trading rejected: Strategy " + signal.strategyId() + " is paused"));
        }

        // 2. Exit Signals are always approved to reduce portfolio risk
        boolean isEntry = signal.signalType() == SignalType.ENTRY_LONG || signal.signalType() == SignalType.ENTRY_SHORT;
        if (!isEntry) {
            return Mono.just(RiskCheckResult.pass());
        }

        // 3. Guardrail 1: Max Daily Loss Checks (Realized Loss + Open Unrealized MTM Drawdown)
        String strategyId = signal.strategyId();
        BigDecimal realizedStratLoss = dailyLossByStrategy.getOrDefault(strategyId, BigDecimal.ZERO);
        BigDecimal openStratLoss = positionManager != null ? positionManager.getTotalUnrealizedLossForStrategy(strategyId) : BigDecimal.ZERO;
        BigDecimal totalStratLoss = realizedStratLoss.add(openStratLoss);

        if (totalStratLoss.compareTo(maxDailyLossPerStrategy) >= 0) {
            String msg = String.format("Strategy %s exceeded max daily loss (Realized: ₹%s + Unrealized: ₹%s = ₹%s >= ₹%s)",
                strategyId, realizedStratLoss, openStratLoss, totalStratLoss, maxDailyLossPerStrategy);
            log.warn(msg);
            dbService.logRiskAudit(strategyId, signal.targetAccountId(), "REJECT_ENTRY", "L1", msg).subscribe(null, e -> {});
            return Mono.just(RiskCheckResult.reject("MAX_STRATEGY_LOSS_LIMIT", msg));
        }

        BigDecimal openGlobalLoss = positionManager != null ? positionManager.getTotalUnrealizedLossGlobal() : BigDecimal.ZERO;
        BigDecimal totalGlobalLoss = dailyLossGlobal.add(openGlobalLoss);

        if (totalGlobalLoss.compareTo(maxDailyLossGlobal) >= 0) {
            String msg = String.format("Global portfolio exceeded max daily loss (Realized: ₹%s + Unrealized: ₹%s = ₹%s >= ₹%s)",
                dailyLossGlobal, openGlobalLoss, totalGlobalLoss, maxDailyLossGlobal);
            log.warn(msg);
            dbService.logRiskAudit(strategyId, signal.targetAccountId(), "REJECT_ENTRY", "L3", msg).subscribe(null, e -> {});
            return Mono.just(RiskCheckResult.reject("MAX_GLOBAL_LOSS_LIMIT", msg));
        }

        // 4. Guardrail 2: Max Open Positions Checks
        int stratOpenPos = openPositionsByStrategy.computeIfAbsent(strategyId, k -> new AtomicInteger(0)).get();
        if (stratOpenPos >= maxOpenPositionsPerStrategy) {
            String msg = String.format("Strategy %s exceeded max open positions (%d >= %d)", strategyId, stratOpenPos, maxOpenPositionsPerStrategy);
            log.warn(msg);
            return Mono.just(RiskCheckResult.reject("MAX_STRATEGY_POSITIONS", msg));
        }

        int totalOpenPos = openPositionsByStrategy.values().stream().mapToInt(AtomicInteger::get).sum();
        if (totalOpenPos >= maxOpenPositionsGlobal) {
            String msg = String.format("Global portfolio exceeded max open positions (%d >= %d)", totalOpenPos, maxOpenPositionsGlobal);
            log.warn(msg);
            return Mono.just(RiskCheckResult.reject("MAX_GLOBAL_POSITIONS", msg));
        }

        // 5. Guardrail 3: Max Single Order Qty & Value Limits
        if (signal.quantity() <= 0 || signal.quantity() > maxOrderQuantity) {
            String msg = String.format("Order quantity %d exceeds maximum allowable single order limit: %d", signal.quantity(), maxOrderQuantity);
            return Mono.just(RiskCheckResult.reject("MAX_ORDER_QTY_LIMIT", msg));
        }

        BigDecimal orderPrice = signal.price() != null ? signal.price() : BigDecimal.ZERO;
        BigDecimal orderValue = orderPrice.multiply(BigDecimal.valueOf(signal.quantity()));
        if (orderValue.compareTo(maxOrderValue) > 0) {
            String msg = String.format("Order value ₹%s exceeds maximum allowable limit ₹%s", orderValue, maxOrderValue);
            return Mono.just(RiskCheckResult.reject("MAX_ORDER_VALUE_LIMIT", msg));
        }

        // 6. Guardrail 4: Price Deviation Check vs Market LTP
        Optional<Candle> lastCandle = marketDataHub.getCandleAggregator().getBuffer(signal.symbol(), "1")
            .flatMap(CircularCandleBuffer::getLast);

        if (lastCandle.isPresent() && lastCandle.get().close().compareTo(BigDecimal.ZERO) > 0 && orderPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ltp = lastCandle.get().close();
            BigDecimal diff = orderPrice.subtract(ltp).abs();
            double deviationPercent = diff.divide(ltp, 4, RoundingMode.HALF_UP).doubleValue() * 100.0;
            if (deviationPercent > maxPriceDeviationPercent) {
                String msg = String.format("Signal price %s deviates by %.2f%% from market LTP %s (max allowed: %.2f%%)",
                    orderPrice, deviationPercent, ltp, maxPriceDeviationPercent);
                log.warn(msg);
                return Mono.just(RiskCheckResult.reject("PRICE_DEVIATION_LIMIT", msg));
            }
        }

        return Mono.just(RiskCheckResult.pass());
    }

    /**
     * Records a realized loss for a strategy and updates global daily loss counters.
     *
     * @param strategyId the identifier of the strategy that incurred the loss
     * @param lossAmount the absolute loss amount in base currency; ignored if null or non-positive
     */
    public void recordRealizedLoss(String strategyId, BigDecimal lossAmount) {
        if (lossAmount != null && lossAmount.compareTo(BigDecimal.ZERO) > 0) {
            dailyLossByStrategy.compute(strategyId, (k, v) -> (v == null ? lossAmount : v.add(lossAmount)));
            dailyLossGlobal = dailyLossGlobal.add(lossAmount);
            log.info("Recorded realized loss of ₹{} for {}. Daily strategy loss: ₹{}, Global loss: ₹{}",
                lossAmount, strategyId, dailyLossByStrategy.get(strategyId), dailyLossGlobal);
        }
    }

    /**
     * Increments the open position counter for the given strategy.
     *
     * @param strategyId the identifier of the strategy whose position count should be incremented
     */
    public void onPositionOpened(String strategyId) {
        if (strategyId != null) {
            openPositionsByStrategy.computeIfAbsent(strategyId, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    /**
     * Decrements the open position counter for the given strategy.
     *
     * @param strategyId the identifier of the strategy whose position count should be decremented
     */
    public void onPositionClosed(String strategyId) {
        if (strategyId != null) {
            openPositionsByStrategy.computeIfPresent(strategyId, (k, v) -> {
                v.decrementAndGet();
                return v;
            });
        }
    }

    /**
     * Activates or deactivates the global panic state in the risk manager.
     *
     * @param active {@code true} to activate global panic, {@code false} to deactivate it
     */
    public void setGlobalPanic(boolean active) {
        this.globalPanicActive.set(active);
        log.warn("RiskManager GLOBAL PANIC state updated: {}", active);
    }

    /**
     * Checks whether the global panic state is currently active.
     *
     * @return {@code true} if global panic is active, {@code false} otherwise
     */
    public boolean isGlobalPanicActive() {
        return globalPanicActive.get();
    }

    /**
     * Freezes a broker account, rejecting all future entry signals targeting it.
     *
     * @param accountId the broker account identifier to freeze (case-insensitive)
     */
    public void freezeBroker(String accountId) {
        if (accountId != null) {
            frozenBrokers.add(accountId.toUpperCase());
            log.warn("RiskManager: Broker account '{}' FROZEN", accountId);
        }
    }

    /**
     * Unfreezes a previously frozen broker account, allowing signals to target it again.
     *
     * @param accountId the broker account identifier to unfreeze (case-insensitive)
     */
    public void unfreezeBroker(String accountId) {
        if (accountId != null) {
            frozenBrokers.remove(accountId.toUpperCase());
            log.info("RiskManager: Broker account '{}' UNFROZEN", accountId);
        }
    }

    /**
     * Pauses a strategy, rejecting all future entry signals from it.
     *
     * @param strategyId the identifier of the strategy to pause
     */
    public void pauseStrategy(String strategyId) {
        if (strategyId != null) {
            pausedStrategies.add(strategyId);
            log.warn("RiskManager: Strategy '{}' PAUSED", strategyId);
        }
    }

    /**
     * Resumes a previously paused strategy, allowing its entry signals to be evaluated again.
     *
     * @param strategyId the identifier of the strategy to resume
     */
    public void resumeStrategy(String strategyId) {
        if (strategyId != null) {
            pausedStrategies.remove(strategyId);
            log.info("RiskManager: Strategy '{}' RESUMED", strategyId);
        }
    }

    /**
     * Resets all daily statistics including per-strategy losses, global loss, and open position counters.
     * Typically invoked at the start of each trading day.
     */
    public void resetDailyStats() {
        dailyLossByStrategy.clear();
        dailyLossGlobal = BigDecimal.ZERO;
        openPositionsByStrategy.clear();
        log.info("RiskManager daily statistics reset");
    }
}
