package com.tradingbot.risk;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3-Tier Hierarchical Emergency Kill Switch:
 * - Level 1 (Strategy): Pauses specific strategy, cancels its open orders, and exits its positions.
 * - Level 2 (Broker Account): Freezes single broker account, cancels all its open orders, and pauses bound strategies.
 * - Level 3 (Global Panic): Instantly cancels all orders, liquidates intraday books, and halts execution.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    private final StrategyEngine strategyEngine;
    private final RiskManager riskManager;
    private final OrderManagerService oms;
    private final PositionManagerService positionManager;
    private final TradingDbService dbService;

    private final AtomicBoolean globalPanic = new AtomicBoolean(false);

    /**
     * Constructs the KillSwitchService with required dependencies.
     *
     * @param strategyEngine the strategy engine for pausing/resuming strategies
     * @param riskManager     the risk manager for emergency state management
     * @param oms             the order manager service for cancelling orders
     * @param positionManager the position manager service for closing positions
     * @param dbService       the database service for audit logging
     */
    public KillSwitchService(
        StrategyEngine strategyEngine,
        RiskManager riskManager,
        OrderManagerService oms,
        PositionManagerService positionManager,
        TradingDbService dbService
    ) {
        this.strategyEngine = strategyEngine;
        this.riskManager = riskManager;
        this.oms = oms;
        this.positionManager = positionManager;
        this.dbService = dbService;
    }

    /**
     * Level 1 Kill Switch: Stop a specific strategy, cancel its open orders, and exit its open positions.
     */
    public Mono<Void> killStrategy(String strategyId, String reason) {
        log.warn("KILL SWITCH L1 TRIGGERED for Strategy '{}' | Reason: {}", strategyId, reason);

        // 1. Pause strategy in engine and RMS
        strategyEngine.pauseStrategy(strategyId);
        riskManager.pauseStrategy(strategyId);

        // 2. Cancel strategy open orders
        List<Order> openOrders = oms.getOpenOrders().stream()
            .filter(o -> strategyId.equalsIgnoreCase(o.strategyId()))
            .toList();

        Mono<Void> cancelOrdersMono = Flux.fromIterable(openOrders)
            .flatMap(o -> oms.cancelOrder(o.id()))
            .then();

        // 3. Liquidate strategy open intraday positions (only those belonging to this strategy)
        List<Position> strategyPositions = positionManager.getOpenIntradayPositions().stream()
            .filter(pos -> strategyId.equalsIgnoreCase(pos.strategyId()) || isPositionOfStrategy(strategyId, pos))
            .toList();

        Mono<Void> exitPositionsMono = Flux.fromIterable(strategyPositions)
            .flatMap(pos -> {
                SignalType sig = pos.netQuantity() > 0 ? SignalType.EXIT_LONG : SignalType.EXIT_SHORT;
                Signal exitSignal = Signal.builder()
                    .strategyId(strategyId)
                    .targetAccountId(pos.accountId())
                    .symbol(pos.symbol())
                    .exchange(pos.exchange())
                    .signalType(sig)
                    .quantity(Math.abs(pos.netQuantity()))
                    .price(pos.ltp())
                    .orderType(OrderType.MARKET)
                    .productType(pos.productType())
                    .bookType(BookType.INTRADAY)
                    .tag("KILL_SWITCH_L1_EXIT")
                    .build();
                return oms.executeSignal(exitSignal);
            })
            .then();

        // 4. Record audit log
        Mono<Void> logMono = dbService.logRiskAudit(strategyId, null, "KILL_STRATEGY", "L1", reason);

        return cancelOrdersMono.then(exitPositionsMono).then(logMono);
    }

    /**
     * Level 2 Kill Switch: Freeze a specific broker account on auth failure or margin call.
     */
    public Mono<Void> freezeBroker(String accountId, String reason) {
        log.warn("KILL SWITCH L2 TRIGGERED for Broker Account '{}' | Reason: {}", accountId, reason);

        // 1. Freeze broker account in RMS
        riskManager.freezeBroker(accountId);

        // 2. Pause all strategies bound to this broker account
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            if (accountId.equalsIgnoreCase(s.getAssignedAccountId())) {
                strategyEngine.pauseStrategy(s.getStrategyId());
                riskManager.pauseStrategy(s.getStrategyId());
            }
        }

        // 3. Cancel all open orders for this account
        Mono<Void> cancelOrdersMono = oms.cancelAllOpenOrders(accountId);

        // 4. Record audit log
        Mono<Void> logMono = dbService.logRiskAudit(null, accountId, "FREEZE_BROKER", "L2", reason);

        return cancelOrdersMono.then(logMono);
    }

    /**
     * Unfreezes a previously frozen broker account, restoring its trading capability.
     *
     * @param accountId the broker account identifier to unfreeze
     * @return a {@link Mono} that completes after the unfreeze event is audit-logged
     */
    public Mono<Void> unfreezeBroker(String accountId) {
        riskManager.unfreezeBroker(accountId);
        log.info("UNFROZEN Broker Account '{}'", accountId);
        return dbService.logRiskAudit(null, accountId, "UNFREEZE_BROKER", "L2", "Operator unfreeze");
    }

    /**
     * Level 3 Kill Switch: GLOBAL PANIC.
     * Cancels all orders across all brokers, liquidates intraday books, and locks execution.
     */
    public Mono<Void> activateGlobalPanic(String reason) {
        log.error("=================================================================");
        log.error("GLOBAL PANIC KILL SWITCH (L3) ACTIVATED! Reason: {}", reason);
        log.error("=================================================================");

        this.globalPanic.set(true);
        riskManager.setGlobalPanic(true);

        // 1. Pause all strategies
        for (Strategy s : strategyEngine.getRegisteredStrategies()) {
            strategyEngine.pauseStrategy(s.getStrategyId());
            riskManager.pauseStrategy(s.getStrategyId());
        }

        // 2. Cancel all open orders across all brokers
        Mono<Void> cancelAllMono = oms.cancelAllOpenOrders(null);

        // 3. Liquidate all intraday positions immediately
        Mono<Void> squareOffMono = positionManager.executeEodIntradaySquareOff();

        // 4. Record audit log in SQLite
        Mono<Void> logMono = dbService.logRiskAudit("ALL", "ALL", "GLOBAL_PANIC", "L3", reason);

        return cancelAllMono.then(squareOffMono).then(logMono);
    }

    /**
     * Deactivates the global panic state, restoring systems to manual/standby mode.
     *
     * @return a {@link Mono} that completes after the deactivation event is audit-logged
     */
    public Mono<Void> deactivateGlobalPanic() {
        this.globalPanic.set(false);
        riskManager.setGlobalPanic(false);
        log.info("GLOBAL PANIC DEACTIVATED. Systems restored to manual/standby mode.");
        return dbService.logRiskAudit("ALL", "ALL", "DEACTIVATE_GLOBAL_PANIC", "L3", "Operator reset");
    }

    /**
     * Checks whether the global panic kill switch is currently active.
     *
     * @return {@code true} if global panic is active, {@code false} otherwise
     */
    public boolean isGlobalPanicActive() {
        return globalPanic.get();
    }

    private boolean isPositionOfStrategy(String strategyId, Position pos) {
        if (pos == null || strategyId == null) return false;
        if (strategyId.equalsIgnoreCase(pos.strategyId())) return true;
        List<Strategy> registered = strategyEngine.getRegisteredStrategies();
        if (registered == null || registered.isEmpty()) {
            return true; // Fallback when strategy engine has no registered beans (e.g. unit tests or standalone execution)
        }
        for (Strategy s : registered) {
            if (strategyId.equalsIgnoreCase(s.getStrategyId())) {
                if (pos.accountId() != null && pos.accountId().equalsIgnoreCase(s.getAssignedAccountId())) {
                    if (s.getSubscribedSymbols() == null || s.getSubscribedSymbols().isEmpty() || s.getSubscribedSymbols().contains(pos.symbol())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
