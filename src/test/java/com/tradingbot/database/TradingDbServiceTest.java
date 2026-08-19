package com.tradingbot.database;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Order;
import com.tradingbot.model.Position;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradingDbServiceTest {

    @TempDir
    Path tempDir;

    private TradingDbService dbService;

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("test_trading_state.db").toFile();
        dbService = new TradingDbService(dbFile.getAbsolutePath());
        dbService.initSchema();
    }

    @Test
    void testOrderPersistence() {
        Order order = Order.builder()
            .id("ORD_001")
            .brokerOrderId("BROKER_12345")
            .accountId("KITE_USER_01")
            .brokerId("ZERODHA")
            .strategyId("VB_01")
            .symbol("NSE:RELIANCE")
            .exchange("NSE")
            .transactionType(TransactionType.BUY)
            .quantity(10)
            .price(new BigDecimal("3050.00"))
            .orderType(OrderType.LIMIT)
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .status(OrderStatus.OPEN)
            .tag("VB_ENTRY")
            .build();

        StepVerifier.create(dbService.saveOrder(order))
            .verifyComplete();

        StepVerifier.create(dbService.findOrderById("ORD_001"))
            .assertNext(found -> {
                assertEquals("ORD_001", found.id());
                assertEquals("BROKER_12345", found.brokerOrderId());
                assertEquals("KITE_USER_01", found.accountId());
                assertEquals("NSE:RELIANCE", found.symbol());
                assertEquals(OrderStatus.OPEN, found.status());
                assertEquals(new BigDecimal("3050.0"), found.price());
            })
            .verifyComplete();

        StepVerifier.create(dbService.findOpenOrders())
            .assertNext(found -> assertEquals("ORD_001", found.id()))
            .verifyComplete();
    }

    @Test
    void testPositionPersistence() {
        Position pos = Position.builder()
            .accountId("KITE_USER_01")
            .brokerId("ZERODHA")
            .symbol("NSE:RELIANCE")
            .productType(ProductType.MIS)
            .bookType(BookType.INTRADAY)
            .netQuantity(10)
            .buyQuantity(10)
            .sellQuantity(0)
            .buyAveragePrice(new BigDecimal("3050.00"))
            .ltp(new BigDecimal("3065.00"))
            .mtmPnl(new BigDecimal("150.00"))
            .realizedPnl(BigDecimal.ZERO)
            .unrealizedPnl(new BigDecimal("150.00"))
            .build();

        StepVerifier.create(dbService.savePosition(pos))
            .verifyComplete();

        StepVerifier.create(dbService.findAllPositions())
            .assertNext(found -> {
                assertEquals("NSE:RELIANCE", found.symbol());
                assertEquals(10, found.netQuantity());
                assertEquals(new BigDecimal("3050.0"), found.buyAveragePrice());
            })
            .verifyComplete();
    }

    @Test
    void testAuthenticHistoricalCandlesStorage() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");
        Candle c1 = new Candle("NSE:RELIANCE", "5", t0, new BigDecimal("3000.00"), new BigDecimal("3020.00"), new BigDecimal("2995.00"), new BigDecimal("3015.00"), 5000L);
        Candle c2 = new Candle("NSE:RELIANCE", "5", t0.plusSeconds(300), new BigDecimal("3015.00"), new BigDecimal("3035.00"), new BigDecimal("3010.00"), new BigDecimal("3030.00"), 8000L);

        StepVerifier.create(dbService.saveHistoricalCandles(List.of(c1, c2)))
            .verifyComplete();

        StepVerifier.create(dbService.loadHistoricalCandles("NSE:RELIANCE", "5", 10))
            .assertNext(c -> {
                assertEquals("NSE:RELIANCE", c.symbol());
                assertEquals(new BigDecimal("3000.0"), c.open());
            })
            .assertNext(c -> {
                assertEquals("NSE:RELIANCE", c.symbol());
                assertEquals(new BigDecimal("3015.0"), c.open());
            })
            .verifyComplete();
    }

    @Test
    void testRiskAuditLogging() {
        StepVerifier.create(dbService.logRiskAudit("VB_01", "KITE_01", "PAUSE_STRATEGY", "L1", "Daily drawdown limit exceeded"))
            .verifyComplete();

        StepVerifier.create(dbService.getRiskAuditLogs())
            .assertNext(rec -> {
                assertEquals("VB_01", rec.strategyId());
                assertEquals("PAUSE_STRATEGY", rec.action());
                assertEquals("L1", rec.level());
            })
            .verifyComplete();
    }
}
