package com.tradingbot.adapter.kite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KiteBrokerAdapterTest {

    private KiteConfig config;
    private KiteAuthenticator authenticator;
    private KiteBrokerAdapter adapter;
    private ObjectMapper objectMapper;
    private InstrumentMasterService instrumentMaster;

    @BeforeEach
    void setUp() {
        config = new KiteConfig();
        config.setEnabled(false); // mock mode
        config.setUserId("TEST_KITE_USER");
        config.setApiKey("test_api_key");
        config.setApiSecret("test_api_secret");

        objectMapper = new ObjectMapper();
        authenticator = new KiteAuthenticator(config, objectMapper);
        instrumentMaster = new InstrumentMasterService("build/tmp/kite-adapter-test-instruments.db");
        instrumentMaster.initSchema();
        adapter = new KiteBrokerAdapter(config, authenticator, WebClient.builder(), objectMapper, instrumentMaster);
    }

    @Test
    void testAuthentication() {
        StepVerifier.create(adapter.authenticate())
            .verifyComplete();

        StepVerifier.create(adapter.isSessionValid())
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void testGetBrokerIdAndAccount() {
        assertEquals("ZERODHA", adapter.getBrokerId());
        assertEquals("TEST_KITE_USER", adapter.getAccountId());
    }

    @Test
    void testSubscribeMarketDataWhenDisabledReturnsEmpty() {
        // Disabled adapter must NOT emit synthetic data — an empty stream is the only safe answer
        StepVerifier.create(adapter.subscribeMarketData(List.of("NFO:NIFTY24DEC24000CE")))
            .verifyComplete();
    }

    @Test
    void testSdkTickMapping() {
        adapter.registerTokenMapping(256265L, "NSE:INFY");

        com.zerodhatech.models.Tick sdkTick = new com.zerodhatech.models.Tick();
        sdkTick.setInstrumentToken(256265L);
        sdkTick.setLastTradedPrice(1534.55);
        sdkTick.setOpenPrice(1520.0);
        sdkTick.setHighPrice(1540.1);
        sdkTick.setLowPrice(1518.25);
        sdkTick.setClosePrice(1522.8);
        sdkTick.setVolumeTradedToday(1234567L);
        sdkTick.setTickTimestamp(new Date());

        Tick mapped = adapter.mapSdkTick(sdkTick);
        assertNotNull(mapped);
        assertEquals("ZERODHA", mapped.brokerId());
        assertEquals("NSE:INFY", mapped.symbol());
        assertEquals("NSE", mapped.exchange());
        assertEquals("256265", mapped.instrumentToken());
        assertEquals(0, new BigDecimal("1534.55").compareTo(mapped.ltp()));
        assertEquals(0, new BigDecimal("1540.1").compareTo(mapped.high()));
        assertEquals(1234567L, mapped.volume());
        assertNotNull(mapped.timestamp());

        // Unknown token must be dropped, not emitted with null symbol
        com.zerodhatech.models.Tick unknown = new com.zerodhatech.models.Tick();
        unknown.setInstrumentToken(999999L);
        unknown.setLastTradedPrice(1.0);
        org.junit.jupiter.api.Assertions.assertNull(adapter.mapSdkTick(unknown));
    }

    @Test
    void testOrderPlacementMockFailureHandling() {
        OrderRequest request = OrderRequest.builder()
            .accountId("TEST_KITE_USER")
            .brokerId("ZERODHA")
            .symbol("NIFTY24DEC24000CE")
            .transactionType(TransactionType.BUY)
            .quantity(50)
            .price(BigDecimal.valueOf(150.50))
            .orderType(OrderType.LIMIT)
            .productType(ProductType.MIS)
            .tag("STRAT_1_BUY")
            .build();

        // In mock/offline mode without real Kite server, WebClient returns graceful error result
        StepVerifier.create(adapter.placeOrder(request))
            .expectNextMatches(result -> !result.success() && result.message() != null)
            .verifyComplete();
    }
}
