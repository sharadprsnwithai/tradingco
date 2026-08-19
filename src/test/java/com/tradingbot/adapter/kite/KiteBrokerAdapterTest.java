package com.tradingbot.adapter.kite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Order;
import com.tradingbot.model.OrderRequest;
import com.tradingbot.model.OrderResult;
import com.tradingbot.model.Position;
import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

class KiteBrokerAdapterTest {

    private KiteConfig config;
    private KiteAuthenticator authenticator;
    private KiteBrokerAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new KiteConfig();
        config.setEnabled(false); // mock mode
        config.setUserId("TEST_KITE_USER");
        config.setApiKey("test_api_key");
        config.setApiSecret("test_api_secret");

        objectMapper = new ObjectMapper();
        authenticator = new KiteAuthenticator(config, objectMapper);
        adapter = new KiteBrokerAdapter(config, authenticator, WebClient.builder(), objectMapper);
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
        org.junit.jupiter.api.Assertions.assertEquals("ZERODHA", adapter.getBrokerId());
        org.junit.jupiter.api.Assertions.assertEquals("TEST_KITE_USER", adapter.getAccountId());
    }

    @Test
    void testSubscribeMarketDataStream() {
        StepVerifier.create(adapter.subscribeMarketData(List.of("NIFTY24DEC24000CE")).take(3))
            .expectNextMatches(tick -> "ZERODHA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .expectNextMatches(tick -> "ZERODHA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .expectNextMatches(tick -> "ZERODHA".equals(tick.brokerId()) && tick.ltp().compareTo(BigDecimal.ZERO) > 0)
            .verifyComplete();
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
