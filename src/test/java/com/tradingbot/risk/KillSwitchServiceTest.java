package com.tradingbot.risk;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.position.PositionManagerService;
import com.tradingbot.strategy.Strategy;
import com.tradingbot.strategy.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KillSwitchServiceTest {

    private StrategyEngine strategyEngine;
    private RiskManager riskManager;
    private OrderManagerService oms;
    private PositionManagerService positionManager;
    private TradingDbService dbService;

    private KillSwitchService killSwitch;

    @BeforeEach
    void setUp() {
        strategyEngine = mock(StrategyEngine.class);
        riskManager = mock(RiskManager.class);
        oms = mock(OrderManagerService.class);
        positionManager = mock(PositionManagerService.class);
        dbService = mock(TradingDbService.class);

        when(dbService.logRiskAudit(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(oms.cancelOrder(anyString())).thenReturn(Mono.empty());
        when(oms.cancelAllOpenOrders(any())).thenReturn(Mono.empty());
        when(oms.executeSignal(any())).thenReturn(Mono.empty());
        when(positionManager.executeEodIntradaySquareOff()).thenReturn(Mono.empty());

        killSwitch = new KillSwitchService(
            strategyEngine,
            riskManager,
            oms,
            positionManager,
            dbService
        );
    }

    @Test
    void testLevel1StrategyKill() {
        Order stratOrder = Order.builder()
            .id("ORD_01")
            .strategyId("VB_01")
            .accountId("KITE_01")
            .status(OrderStatus.OPEN)
            .build();

        when(oms.getOpenOrders()).thenReturn(List.of(stratOrder));

        Position pos = Position.builder()
            .accountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .netQuantity(10)
            .ltp(new BigDecimal("3000.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .build();

        when(positionManager.getOpenIntradayPositions()).thenReturn(List.of(pos));

        StepVerifier.create(killSwitch.killStrategy("VB_01", "Max drawdown breached"))
            .verifyComplete();

        verify(strategyEngine, times(1)).pauseStrategy("VB_01");
        verify(riskManager, times(1)).pauseStrategy("VB_01");
        verify(oms, times(1)).cancelOrder("ORD_01");
        verify(oms, times(1)).executeSignal(argThat(sig -> "VB_01".equals(sig.strategyId())));
        verify(dbService, times(1)).logRiskAudit("VB_01", null, "KILL_STRATEGY", "L1", "Max drawdown breached");
    }

    @Test
    void testLevel2BrokerFreeze() {
        Strategy boundStrategy = mock(Strategy.class);
        when(boundStrategy.getStrategyId()).thenReturn("VB_01");
        when(boundStrategy.getAssignedAccountId()).thenReturn("KITE_01");

        when(strategyEngine.getRegisteredStrategies()).thenReturn(List.of(boundStrategy));

        StepVerifier.create(killSwitch.freezeBroker("KITE_01", "Session auth expired"))
            .verifyComplete();

        verify(riskManager, times(1)).freezeBroker("KITE_01");
        verify(strategyEngine, times(1)).pauseStrategy("VB_01");
        verify(oms, times(1)).cancelAllOpenOrders("KITE_01");
        verify(dbService, times(1)).logRiskAudit(null, "KITE_01", "FREEZE_BROKER", "L2", "Session auth expired");
    }

    @Test
    void testLevel3GlobalPanic() {
        Strategy strat1 = mock(Strategy.class);
        when(strat1.getStrategyId()).thenReturn("VB_01");
        when(strategyEngine.getRegisteredStrategies()).thenReturn(List.of(strat1));

        StepVerifier.create(killSwitch.activateGlobalPanic("Extreme market freak event"))
            .verifyComplete();

        assertTrue(killSwitch.isGlobalPanicActive());
        verify(riskManager, times(1)).setGlobalPanic(true);
        verify(strategyEngine, times(1)).pauseStrategy("VB_01");
        verify(oms, times(1)).cancelAllOpenOrders(null);
        verify(positionManager, times(1)).executeEodIntradaySquareOff();
        verify(dbService, times(1)).logRiskAudit("ALL", "ALL", "GLOBAL_PANIC", "L3", "Extreme market freak event");

        // Deactivate panic
        StepVerifier.create(killSwitch.deactivateGlobalPanic())
            .verifyComplete();

        assertFalse(killSwitch.isGlobalPanicActive());
        verify(riskManager, times(1)).setGlobalPanic(false);
    }
}
