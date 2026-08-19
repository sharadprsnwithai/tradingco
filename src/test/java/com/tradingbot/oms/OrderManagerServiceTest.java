package com.tradingbot.oms;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.database.TradingDbService;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Instrument;
import com.tradingbot.model.Order;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.risk.RiskCheckResult;
import com.tradingbot.risk.RiskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderManagerServiceTest {

    private BrokerAdapterRegistry brokerRegistry;
    private TradingDbService dbService;
    private RiskManager riskManager;
    private InstrumentMasterService instrumentMaster;
    private MarketDataHub marketDataHub;
    private BrokerAdapter mockAdapter;

    private OrderManagerService oms;

    @BeforeEach
    void setUp() {
        brokerRegistry = mock(BrokerAdapterRegistry.class);
        dbService = mock(TradingDbService.class);
        riskManager = mock(RiskManager.class);
        instrumentMaster = mock(InstrumentMasterService.class);
        marketDataHub = mock(MarketDataHub.class);
        mockAdapter = mock(BrokerAdapter.class);

        when(mockAdapter.getBrokerId()).thenReturn("ZERODHA");
        when(mockAdapter.getAccountId()).thenReturn("KITE_USER_01");

        when(brokerRegistry.getByAccountId(anyString())).thenReturn(Mono.just(mockAdapter));
        when(brokerRegistry.getByBrokerId(anyString())).thenReturn(Mono.just(mockAdapter));
        when(brokerRegistry.getAll()).thenReturn(Flux.just(mockAdapter));

        when(dbService.saveOrder(any())).thenReturn(Mono.empty());
        when(riskManager.validateSignal(any())).thenReturn(Mono.just(RiskCheckResult.pass()));

        when(instrumentMaster.findByCanonicalSymbol("NSE:RELIANCE")).thenReturn(Mono.just(
            Instrument.builder()
                .canonicalSymbol("NSE:RELIANCE")
                .kiteToken("738561")
                .tickSize(new BigDecimal("0.05"))
                .build()
        ));

        when(marketDataHub.getCandleAggregator()).thenReturn(new CandleAggregator());

        oms = new OrderManagerService(
            brokerRegistry,
            dbService,
            riskManager,
            instrumentMaster,
            marketDataHub,
            0.5, // 0.5% slippage buffer
            4,   // 4s reconciler interval
            false // paperTrading = false default
        );
    }

    @Test
    void testPaperTradingExecution() {
        oms.setPaperTrading(true);
        assertTrue(oms.isPaperTrading());

        Signal buySignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_USER_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("3000.00"))
            .tag("PAPER_TEST")
            .build();

        StepVerifier.create(oms.executeSignal(buySignal))
            .assertNext(order -> {
                assertNotNull(order.id());
                assertTrue(order.brokerOrderId().startsWith("PAPER_"));
                assertEquals("PAPER_BROKER", order.brokerId());
                assertEquals(OrderStatus.FILLED, order.status());
                assertEquals(10, order.filledQuantity());
                assertEquals(new BigDecimal("3015.00"), order.price());
            })
            .verifyComplete();

        verify(mockAdapter, never()).placeOrder(any());
    }

    @Test
    void testExecuteSignalMarketableLimitOrderPlacement() {
        when(mockAdapter.placeOrder(any())).thenReturn(Mono.just(
            OrderResult.success("BROKER_ORDER_999", "TEST_TAG", OrderStatus.OPEN)
        ));

        Signal buySignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_USER_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("3000.00"))
            .tag("TEST_TAG")
            .build();

        StepVerifier.create(oms.executeSignal(buySignal))
            .assertNext(order -> {
                assertNotNull(order.id());
                assertEquals("BROKER_ORDER_999", order.brokerOrderId());
                assertEquals("KITE_USER_01", order.accountId());
                assertEquals("ZERODHA", order.brokerId());
                assertEquals("NSE:RELIANCE", order.symbol());
                assertEquals(10, order.quantity());
                assertEquals(OrderStatus.OPEN, order.status());

                // Marketable LIMIT: 3000 * 1.005 = 3015.00 (rounded to 0.05)
                assertEquals(new BigDecimal("3015.00"), order.price());
            })
            .verifyComplete();

        assertEquals(1, oms.getOpenOrders().size());
    }

    @Test
    void testExecuteSignalRejectedByRiskManager() {
        when(riskManager.validateSignal(any())).thenReturn(
            Mono.just(RiskCheckResult.reject("MAX_STRATEGY_LOSS_LIMIT", "Loss limit breached"))
        );

        Signal signal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_USER_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("3000.00"))
            .build();

        StepVerifier.create(oms.executeSignal(signal))
            .assertNext(order -> {
                assertEquals(OrderStatus.REJECTED, order.status());
                assertTrue(order.statusMessage().contains("MAX_STRATEGY_LOSS_LIMIT"));
            })
            .verifyComplete();
    }

    @Test
    void testCancelOpenOrder() {
        when(mockAdapter.placeOrder(any())).thenReturn(Mono.just(
            OrderResult.success("BROKER_ORDER_999", "TEST_TAG", OrderStatus.OPEN)
        ));
        when(mockAdapter.cancelOrder(anyString())).thenReturn(Mono.empty());

        Signal buySignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_USER_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("3000.00"))
            .build();

        Order placed = oms.executeSignal(buySignal).block();
        assertNotNull(placed);

        StepVerifier.create(oms.cancelOrder(placed.id()))
            .verifyComplete();

        Order updated = oms.getOrder(placed.id()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, updated.status());
    }

    @Test
    void testTickSizeRounding() {
        BigDecimal price = new BigDecimal("100.123");
        BigDecimal tickSize = new BigDecimal("0.05");
        BigDecimal rounded = OrderManagerService.roundToTick(price, tickSize);
        assertEquals(new BigDecimal("100.10"), rounded);

        BigDecimal price2 = new BigDecimal("100.14");
        BigDecimal rounded2 = OrderManagerService.roundToTick(price2, tickSize);
        assertEquals(new BigDecimal("100.15"), rounded2);
    }
}
