package com.tradingbot.risk;

import com.tradingbot.database.TradingDbService;
import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.enums.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskManagerTest {

    private TradingDbService dbService;
    private MarketDataHub marketDataHub;
    private CandleAggregator candleAggregator;
    private RiskManager riskManager;

    @BeforeEach
    void setUp() {
        dbService = mock(TradingDbService.class);
        when(dbService.logRiskAudit(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        marketDataHub = mock(MarketDataHub.class);
        candleAggregator = new CandleAggregator();
        when(marketDataHub.getCandleAggregator()).thenReturn(candleAggregator);

        riskManager = new RiskManager(
            dbService,
            marketDataHub,
            5000.0,   // maxDailyLossPerStrategy
            15000.0,  // maxDailyLossGlobal
            2,        // maxOpenPositionsPerStrategy
            5,        // maxOpenPositionsGlobal
            100,      // maxOrderQuantity
            100000.0, // maxOrderValue
            3.0       // maxPriceDeviationPercent
        );
    }

    @Test
    void testValidSignalApproved() {
        Signal validSignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("2500.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(validSignal))
            .assertNext(res -> {
                assertTrue(res.approved());
                assertEquals("PASS", res.ruleName());
            })
            .verifyComplete();
    }

    @Test
    void testMaxDailyLossRejection() {
        // Record ₹5000 loss for VB_01
        riskManager.recordRealizedLoss("VB_01", new BigDecimal("5000.00"));

        Signal signal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("2500.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(signal))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("MAX_STRATEGY_LOSS_LIMIT", res.ruleName());
            })
            .verifyComplete();

        // Exit signal should still be allowed
        Signal exitSignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.EXIT_LONG)
            .quantity(10)
            .price(new BigDecimal("2500.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(exitSignal))
            .assertNext(res -> assertTrue(res.approved()))
            .verifyComplete();
    }

    @Test
    void testMaxOpenPositionsRejection() {
        riskManager.onPositionOpened("VB_01");
        riskManager.onPositionOpened("VB_01"); // 2 positions open (limit reached)

        Signal signal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:INFY")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("1800.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(signal))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("MAX_STRATEGY_POSITIONS", res.ruleName());
            })
            .verifyComplete();

        // Close one position -> should allow entry again
        riskManager.onPositionClosed("VB_01");
        StepVerifier.create(riskManager.validateSignal(signal))
            .assertNext(res -> assertTrue(res.approved()))
            .verifyComplete();
    }

    @Test
    void testMaxQuantityAndOrderValueRejection() {
        Signal excessiveQty = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(150) // limit is 100
            .price(new BigDecimal("500.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(excessiveQty))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("MAX_ORDER_QTY_LIMIT", res.ruleName());
            })
            .verifyComplete();

        Signal excessiveVal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(50)
            .price(new BigDecimal("2500.00")) // 50 * 2500 = ₹125,000 > ₹100,000 limit
            .build();

        StepVerifier.create(riskManager.validateSignal(excessiveVal))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("MAX_ORDER_VALUE_LIMIT", res.ruleName());
            })
            .verifyComplete();
    }

    @Test
    void testPriceDeviationCheck() {
        // Seed 1m candle with LTP = 2500
        candleAggregator.getOrCreateBuffer("NSE:RELIANCE", "1").add(
            new Candle("NSE:RELIANCE", "1", Instant.now(), new BigDecimal("2500"), new BigDecimal("2510"), new BigDecimal("2495"), new BigDecimal("2500"), 1000L)
        );

        // Signal with price 2650 (> 3% deviation from 2500: 6% diff)
        Signal deviatedSignal = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("2650.00"))
            .build();

        StepVerifier.create(riskManager.validateSignal(deviatedSignal))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("PRICE_DEVIATION_LIMIT", res.ruleName());
            })
            .verifyComplete();
    }

    @Test
    void testEmergencyKillSwitchRejections() {
        Signal entry = Signal.builder()
            .strategyId("VB_01")
            .targetAccountId("KITE_01")
            .symbol("NSE:RELIANCE")
            .signalType(SignalType.ENTRY_LONG)
            .quantity(10)
            .price(new BigDecimal("2500.00"))
            .build();

        // 1. Global Panic
        riskManager.setGlobalPanic(true);
        StepVerifier.create(riskManager.validateSignal(entry))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("GLOBAL_PANIC", res.ruleName());
            })
            .verifyComplete();
        riskManager.setGlobalPanic(false);

        // 2. Frozen Broker
        riskManager.freezeBroker("KITE_01");
        StepVerifier.create(riskManager.validateSignal(entry))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("BROKER_FROZEN", res.ruleName());
            })
            .verifyComplete();
        riskManager.unfreezeBroker("KITE_01");

        // 3. Paused Strategy
        riskManager.pauseStrategy("VB_01");
        StepVerifier.create(riskManager.validateSignal(entry))
            .assertNext(res -> {
                assertFalse(res.approved());
                assertEquals("STRATEGY_PAUSED", res.ruleName());
            })
            .verifyComplete();
    }
}
