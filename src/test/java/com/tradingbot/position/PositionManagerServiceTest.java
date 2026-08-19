package com.tradingbot.position;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import com.tradingbot.oms.OrderManagerService;
import com.tradingbot.strategy.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PositionManagerServiceTest {

    private BrokerAdapterRegistry brokerRegistry;
    private OrderManagerService oms;
    private TradingDbService dbService;
    private MarketDataHub marketDataHub;
    private StrategyEngine strategyEngine;

    private Sinks.Many<Order> orderSink;
    private Sinks.Many<Tick> tickSink;

    private PositionManagerService positionManager;

    @BeforeEach
    void setUp() {
        brokerRegistry = mock(BrokerAdapterRegistry.class);
        oms = mock(OrderManagerService.class);
        dbService = mock(TradingDbService.class);
        marketDataHub = mock(MarketDataHub.class);
        strategyEngine = mock(StrategyEngine.class);

        orderSink = Sinks.many().multicast().directBestEffort();
        tickSink = Sinks.many().multicast().directBestEffort();

        when(oms.getOrderStream()).thenReturn(orderSink.asFlux());
        when(oms.executeSignal(any())).thenReturn(Mono.empty());
        when(marketDataHub.getTickStream()).thenReturn(tickSink.asFlux());
        when(brokerRegistry.getAll()).thenReturn(Flux.empty());
        when(dbService.savePosition(any())).thenReturn(Mono.empty());

        positionManager = new PositionManagerService(
            brokerRegistry,
            oms,
            dbService,
            marketDataHub,
            strategyEngine
        );
        positionManager.init();
    }

    @Test
    void testIntradayAndPositionalBookSeparation() {
        // 1. Fill an Intraday Order (MIS)
        Order misOrder = Order.builder()
            .id("ORD_MIS_01")
            .accountId("KITE_USER_01")
            .brokerId("ZERODHA")
            .symbol("NSE:RELIANCE")
            .transactionType(TransactionType.BUY)
            .quantity(10)
            .filledQuantity(10)
            .price(new BigDecimal("3000.00"))
            .averagePrice(new BigDecimal("3000.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .status(OrderStatus.FILLED)
            .build();

        positionManager.onOrderFilled(misOrder);

        assertEquals(1, positionManager.getOpenIntradayPositions().size());
        assertEquals(0, positionManager.getOpenPositionalPositions().size());

        // 2. Fill a Positional Order (NRML)
        Order nrmlOrder = Order.builder()
            .id("ORD_NRML_01")
            .accountId("KITE_USER_01")
            .brokerId("ZERODHA")
            .symbol("NFO:NIFTY24DEC24500CE")
            .transactionType(TransactionType.BUY)
            .quantity(25)
            .filledQuantity(25)
            .price(new BigDecimal("150.00"))
            .averagePrice(new BigDecimal("150.00"))
            .productType(ProductType.NRML)
            .bookType(BookType.POSITIONAL)
            .status(OrderStatus.FILLED)
            .build();

        positionManager.onOrderFilled(nrmlOrder);

        assertEquals(1, positionManager.getOpenIntradayPositions().size());
        assertEquals(1, positionManager.getOpenPositionalPositions().size());
    }

    @Test
    void testLiveMtmUpdateOnTick() {
        Order buyOrder = Order.builder()
            .id("ORD_01")
            .accountId("KITE_USER_01")
            .brokerId("ZERODHA")
            .symbol("NSE:RELIANCE")
            .transactionType(TransactionType.BUY)
            .quantity(10)
            .filledQuantity(10)
            .price(new BigDecimal("3000.00"))
            .averagePrice(new BigDecimal("3000.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .status(OrderStatus.FILLED)
            .build();

        positionManager.onOrderFilled(buyOrder);

        // Tick arrives at 3020 (+20 points gain on 10 qty = +₹200 MTM)
        Tick tick = Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("3020.00"))
            .timestamp(Instant.now())
            .build();

        positionManager.onTick(tick);

        Position pos = positionManager.getPosition("KITE_USER_01", "NSE:RELIANCE", ProductType.MIS).orElseThrow();
        assertEquals(new BigDecimal("3020.00"), pos.ltp());
        assertEquals(new BigDecimal("200.00"), pos.unrealizedPnl());
        assertEquals(new BigDecimal("200.00"), pos.mtmPnl());
    }

    @Test
    void testEodIntradaySquareOffProtectsPositionalBook() {
        // Intraday position
        Order misOrder = Order.builder()
            .id("ORD_MIS_01")
            .accountId("KITE_USER_01")
            .symbol("NSE:RELIANCE")
            .transactionType(TransactionType.BUY)
            .quantity(10)
            .filledQuantity(10)
            .price(new BigDecimal("3000.00"))
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .status(OrderStatus.FILLED)
            .build();
        positionManager.onOrderFilled(misOrder);

        // Positional position
        Order cncOrder = Order.builder()
            .id("ORD_CNC_01")
            .accountId("KITE_USER_01")
            .symbol("NSE:TCS")
            .transactionType(TransactionType.BUY)
            .quantity(5)
            .filledQuantity(5)
            .price(new BigDecimal("4000.00"))
            .productType(ProductType.CNC)
            .bookType(BookType.POSITIONAL)
            .status(OrderStatus.FILLED)
            .build();
        positionManager.onOrderFilled(cncOrder);

        // Trigger 15:18 EOD Square-off
        StepVerifier.create(positionManager.executeEodIntradaySquareOff())
            .verifyComplete();

        // Verify oms.executeSignal was called for the Intraday RELIANCE position
        verify(oms, times(1)).executeSignal(argThat(sig ->
            "NSE:RELIANCE".equals(sig.symbol()) &&
            sig.quantity() == 10 &&
            BookType.INTRADAY == sig.bookType()
        ));

        // Verify Positional TCS was NOT touched
        verify(oms, never()).executeSignal(argThat(sig ->
            "NSE:TCS".equals(sig.symbol())
        ));
    }
}
