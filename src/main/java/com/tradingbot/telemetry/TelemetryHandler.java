package com.tradingbot.telemetry;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Position;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.KillSwitchService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reactive WebFlux Handler for Telemetry REST APIs and Server-Sent Events (SSE) streaming.
 */
@Component
public class TelemetryHandler {

    private final StrategyEngine strategyEngine;
    private final OrderManagerService oms;
    private final PositionManagerService positionManager;
    private final KillSwitchService killSwitch;
    private final MarketDataHub marketDataHub;
    private final TradingDbService dbService;

    /**
     * Constructs the TelemetryHandler with required service dependencies.
     *
     * @param strategyEngine   strategy engine for strategy status retrieval
     * @param oms              order manager service for orders and paper trading toggle
     * @param positionManager  position manager for position and P&L data
     * @param killSwitch       kill switch for panic and freeze operations
     * @param marketDataHub    market data hub for broker status
     * @param dbService        database service for risk audit logs
     */
    public TelemetryHandler(
        StrategyEngine strategyEngine,
        OrderManagerService oms,
        PositionManagerService positionManager,
        KillSwitchService killSwitch,
        MarketDataHub marketDataHub,
        TradingDbService dbService
    ) {
        this.strategyEngine = strategyEngine;
        this.oms = oms;
        this.positionManager = positionManager;
        this.killSwitch = killSwitch;
        this.marketDataHub = marketDataHub;
        this.dbService = dbService;
    }

    /**
     * Returns a full telemetry snapshot including system status, positions, P&L, and strategies.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON telemetry snapshot response
     */
    public Mono<ServerResponse> getSummary(ServerRequest request) {
        return buildTelemetrySnapshot()
            .flatMap(snapshot -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(snapshot));
    }

    /**
     * Returns all open positions grouped by intraday, positional, and combined.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON positions response
     */
    public Mono<ServerResponse> getPositions(ServerRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put("intraday", positionManager.getOpenIntradayPositions());
        data.put("positional", positionManager.getOpenPositionalPositions());
        data.put("all", positionManager.getAllPositions());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(data);
    }

    /**
     * Returns all open orders from the order manager service.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON open orders response
     */
    public Mono<ServerResponse> getOrders(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(oms.getOpenOrders());
    }

    /**
     * Returns risk audit logs from the database service.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON risk audit logs response
     */
    public Mono<ServerResponse> getRiskLogs(ServerRequest request) {
        return dbService.getRiskAuditLogs().collectList()
            .flatMap(logs -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(logs));
    }

    /**
     * Kills a specific strategy by ID via the kill switch service.
     *
     * @param request the incoming HTTP request containing the strategy ID path variable
     * @return a Mono emitting the JSON kill confirmation response
     */
    public Mono<ServerResponse> killStrategy(ServerRequest request) {
        String strategyId = request.pathVariable("id");
        return killSwitch.killStrategy(strategyId, "WebDesk manual kill")
            .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "SUCCESS", "message", "Strategy " + strategyId + " killed")));
    }

    /**
     * Freezes a specific broker by ID via the kill switch service.
     *
     * @param request the incoming HTTP request containing the broker ID path variable
     * @return a Mono emitting the JSON freeze confirmation response
     */
    public Mono<ServerResponse> freezeBroker(ServerRequest request) {
        String brokerId = request.pathVariable("id");
        return killSwitch.freezeBroker(brokerId, "WebDesk manual freeze")
            .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "SUCCESS", "message", "Broker " + brokerId + " frozen")));
    }

    /**
     * Triggers the global panic liquidation via the kill switch service.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON global panic activation response
     */
    public Mono<ServerResponse> triggerGlobalPanic(ServerRequest request) {
        return killSwitch.activateGlobalPanic("WebDesk operator panic button")
            .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "GLOBAL_PANIC_ACTIVATED")));
    }

    /**
     * Deactivates the global panic state via the kill switch service.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON global panic deactivation response
     */
    public Mono<ServerResponse> resetGlobalPanic(ServerRequest request) {
        return killSwitch.deactivateGlobalPanic()
            .then(ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("status", "GLOBAL_PANIC_DEACTIVATED")));
    }

    /**
     * Toggles a strategy between enabled and disabled states.
     *
     * @param request the incoming HTTP request containing the strategy ID path variable
     * @return a Mono emitting the JSON toggle confirmation, or 404 if strategy not found
     */
    public Mono<ServerResponse> toggleStrategy(ServerRequest request) {
        String strategyId = request.pathVariable("id");
        return strategyEngine.getStrategy(strategyId)
            .map(s -> {
                boolean target = !s.isEnabled();
                if (target) {
                    strategyEngine.resumeStrategy(strategyId);
                } else {
                    strategyEngine.pauseStrategy(strategyId);
                }
                return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("strategyId", strategyId, "enabled", target));
            })
            .orElseGet(() -> ServerResponse.notFound().build());
    }

    /**
     * Toggles paper trading mode on or off in the order manager service.
     *
     * @param request the incoming HTTP request
     * @return a Mono emitting the JSON paper trading toggle response
     */
    public Mono<ServerResponse> togglePaperTrading(ServerRequest request) {
        boolean target = !oms.isPaperTrading();
        oms.setPaperTrading(target);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("paperTrading", target));
    }

    /**
     * Server-Sent Events (SSE) stream emitting telemetry snapshots every 1 second.
     */
    public Mono<ServerResponse> streamTelemetry(ServerRequest request) {
        Flux<ServerSentEvent<Map<String, Object>>> eventFlux = Flux.interval(Duration.ofSeconds(1))
            .flatMap(tick -> buildTelemetrySnapshot())
            .map(snapshot -> ServerSentEvent.<Map<String, Object>>builder()
                .id(String.valueOf(System.currentTimeMillis()))
                .event("telemetry-update")
                .data(snapshot)
                .build());

        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(eventFlux, ServerSentEvent.class);
    }

    /**
     * Builds a complete telemetry snapshot containing system status, positions, P&L, orders, and strategies.
     *
     * @return a Mono emitting the telemetry snapshot map
     */
    private Mono<Map<String, Object>> buildTelemetrySnapshot() {
        return Mono.fromCallable(() -> {
            Map<String, Object> snap = new HashMap<>();

            // System & Broker status
            snap.put("activeBroker", marketDataHub.getActiveBroker());
            snap.put("isFailedOver", marketDataHub.isFailedOver());
            snap.put("paperTrading", oms.isPaperTrading());
            snap.put("globalPanic", killSwitch.isGlobalPanicActive());

            // Positions & P&L
            List<Position> intraday = positionManager.getOpenIntradayPositions();
            List<Position> positional = positionManager.getOpenPositionalPositions();
            BigDecimal intradayPnl = intraday.stream().map(Position::mtmPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal positionalPnl = positional.stream().map(Position::mtmPnl).reduce(BigDecimal.ZERO, BigDecimal::add);

            snap.put("intradayPositions", intraday);
            snap.put("positionalPositions", positional);
            snap.put("intradayPnl", intradayPnl);
            snap.put("positionalPnl", positionalPnl);
            snap.put("totalMtmPnl", intradayPnl.add(positionalPnl));

            // Orders
            snap.put("openOrders", oms.getOpenOrders());

            // Strategies
            List<Map<String, Object>> stratList = strategyEngine.getRegisteredStrategies().stream()
                .map(s -> {
                    Map<String, Object> smap = new HashMap<>();
                    smap.put("strategyId", s.getStrategyId());
                    smap.put("accountId", s.getAssignedAccountId());
                    smap.put("enabled", s.isEnabled());
                    smap.put("symbols", s.getSubscribedSymbols());
                    return smap;
                })
                .toList();
            snap.put("strategies", stratList);

            snap.put("timestamp", System.currentTimeMillis());
            return snap;
        });
    }
}
