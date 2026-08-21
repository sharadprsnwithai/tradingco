package com.tradingbot.adapter.shoonya;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShoonyaBrokerAdapterTest {

    private ShoonyaConfig config;
    private ShoonyaAuthenticator authenticator;
    private ShoonyaBrokerAdapter adapter;
    private ObjectMapper objectMapper;
    private InstrumentMasterService instrumentMaster;

    @BeforeEach
    void setUp() {
        config = new ShoonyaConfig();
        config.setEnabled(false); // mock / offline test mode
        config.setUserId("FA12345");
        config.setAccountId("FA12345_ACT");
        config.setClientId("FA12345_CLIENT");
        config.setSecretKey("test_secret_key");

        objectMapper = new ObjectMapper();
        authenticator = new ShoonyaAuthenticator(config, WebClient.builder(), objectMapper);
        instrumentMaster = new InstrumentMasterService("build/tmp/shoonya-adapter-test-instruments.db");
        instrumentMaster.initSchema();
        adapter = new ShoonyaBrokerAdapter(config, authenticator, WebClient.builder(), objectMapper, instrumentMaster);
    }

    @Test
    void testAuthenticationAndSession() {
        StepVerifier.create(adapter.authenticate())
            .verifyComplete();

        StepVerifier.create(adapter.isSessionValid())
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void testGetBrokerIdAndAccount() {
        assertEquals("SHOONYA", adapter.getBrokerId());
        assertEquals("FA12345_ACT", adapter.getAccountId());
    }

    @Test
    void testSubscribeMarketDataWhenDisabledReturnsEmpty() {
        // Disabled adapter must NOT emit synthetic data — an empty stream is the only safe answer
        StepVerifier.create(adapter.subscribeMarketData(List.of("NFO:BANKNIFTY24DEC50000CE")))
            .verifyComplete();
    }

    @Test
    void testShoonyaTickMapping() throws Exception {
        adapter.registerKeyMapping("NSE|22", "NSE:ACC");

        String json = "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"22\",\"lp\":\"2450.55\",\"o\":\"2401.0\",\"h\":\"2460.0\",\"l\":\"2398.0\",\"c\":\"2410.0\",\"v\":\"123456\"}";
        Tick mapped = adapter.mapShoonyaTick(new ObjectMapper().readTree(json));

        assertNotNull(mapped);
        assertEquals("SHOONYA", mapped.brokerId());
        assertEquals("NSE:ACC", mapped.symbol());
        assertEquals("NSE", mapped.exchange());
        assertEquals("22", mapped.instrumentToken());
        assertEquals(0, new BigDecimal("2450.55").compareTo(mapped.ltp()));
        assertEquals(0, new BigDecimal("2460.0").compareTo(mapped.high()));
        assertEquals(123456L, mapped.volume());

        // Unknown token must be dropped
        String unknownJson = "{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"99999\",\"lp\":\"100.0\"}";
        org.junit.jupiter.api.Assertions.assertNull(adapter.mapShoonyaTick(new ObjectMapper().readTree(unknownJson)));
    }

    @Test
    void testOrderPlacementMockFailureHandling() {
        OrderRequest request = OrderRequest.builder()
            .accountId("FA12345_ACT")
            .brokerId("SHOONYA")
            .symbol("BANKNIFTY24DEC50000CE")
            .exchange("NFO")
            .transactionType(TransactionType.BUY)
            .quantity(30)
            .price(BigDecimal.valueOf(250.00))
            .orderType(OrderType.LIMIT)
            .productType(ProductType.MIS)
            .tag("SHOONYA_STRAT_1")
            .build();

        // In offline mode without live Shoonya server, returns graceful failure result
        StepVerifier.create(adapter.placeOrder(request))
            .expectNextMatches(result -> !result.success() && result.message() != null)
            .verifyComplete();
    }
}
