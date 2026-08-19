package com.tradingbot.telemetry;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Position;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.risk.KillSwitchService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class TelemetryRouterTest {

    private StrategyEngine strategyEngine;
    private OrderManagerService oms;
    private PositionManagerService positionManager;
    private KillSwitchService killSwitch;
    private MarketDataHub marketDataHub;
    private TradingDbService dbService;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        strategyEngine = mock(StrategyEngine.class);
        oms = mock(OrderManagerService.class);
        positionManager = mock(PositionManagerService.class);
        killSwitch = mock(KillSwitchService.class);
        marketDataHub = mock(MarketDataHub.class);
        dbService = mock(TradingDbService.class);

        when(marketDataHub.getActiveBroker()).thenReturn("ZERODHA");
        when(marketDataHub.isFailedOver()).thenReturn(false);
        when(oms.isPaperTrading()).thenReturn(true);
        when(killSwitch.isGlobalPanicActive()).thenReturn(false);

        when(positionManager.getOpenIntradayPositions()).thenReturn(List.of());
        when(positionManager.getOpenPositionalPositions()).thenReturn(List.of());
        when(positionManager.getAllPositions()).thenReturn(List.of());
        when(oms.getOpenOrders()).thenReturn(List.of());
        when(strategyEngine.getRegisteredStrategies()).thenReturn(List.of());
        when(dbService.getRiskAuditLogs()).thenReturn(Flux.empty());

        TelemetryHandler handler = new TelemetryHandler(
            strategyEngine,
            oms,
            positionManager,
            killSwitch,
            marketDataHub,
            dbService
        );

        TelemetryRouter router = new TelemetryRouter();
        this.webTestClient = WebTestClient.bindToRouterFunction(router.telemetryRoutes(handler)).build();
    }

    @Test
    void testGetSummary() {
        webTestClient.get()
            .uri("/api/telemetry/summary")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.activeBroker").isEqualTo("ZERODHA")
            .jsonPath("$.paperTrading").isEqualTo(true)
            .jsonPath("$.globalPanic").isEqualTo(false);
    }

    @Test
    void testGetPositions() {
        Position pos = Position.builder()
            .accountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .netQuantity(10)
            .buyAveragePrice(new BigDecimal("3000.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .build();

        when(positionManager.getOpenIntradayPositions()).thenReturn(List.of(pos));

        webTestClient.get()
            .uri("/api/telemetry/positions")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.intraday[0].symbol").isEqualTo("NSE:RELIANCE");
    }

    @Test
    void testKillSwitchPanicEndpoint() {
        when(killSwitch.activateGlobalPanic(anyString())).thenReturn(Mono.empty());

        webTestClient.post()
            .uri("/api/telemetry/kill-switch/panic")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("GLOBAL_PANIC_ACTIVATED");

        verify(killSwitch, times(1)).activateGlobalPanic("WebDesk operator panic button");
    }

    @Test
    void testTogglePaperTradingEndpoint() {
        when(oms.isPaperTrading()).thenReturn(true);

        webTestClient.post()
            .uri("/api/telemetry/paper-trading/toggle")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.paperTrading").isEqualTo(false);

        verify(oms, times(1)).setPaperTrading(false);
    }
}
