package com.tradingbot.adapter.shoonya;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
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
        adapter = new ShoonyaBrokerAdapter(config, authenticator, WebClient.builder(), objectMapper);
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
    void testSubscribeMarketDataStream() {
        StepVerifier.create(adapter.subscribeMarketData(List.of("BANKNIFTY24DEC50000CE")).take(3))
            .expectNextMatches(tick -> "SHOONYA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .expectNextMatches(tick -> "SHOONYA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .expectNextMatches(tick -> "SHOONYA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .verifyComplete();
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
