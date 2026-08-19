package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.shoonya.ShoonyaAuthenticator;
import com.tradingbot.adapter.shoonya.ShoonyaConfig;
import com.tradingbot.model.Candle;
import com.tradingbot.resilience.BrokerBulkheadManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShoonyaHistoricalDataServiceTest {

    private ShoonyaConfig config;
    private ShoonyaAuthenticator authenticator;
    private BrokerBulkheadManager bulkheadManager;
    private ObjectMapper objectMapper;
    private ShoonyaHistoricalDataService service;

    @BeforeEach
    void setUp() {
        config = new ShoonyaConfig();
        config.setEnabled(false); // test mock mode
        config.setUserId("FA12345");
        config.setAccountId("FA12345");

        authenticator = mock(ShoonyaAuthenticator.class);
        when(authenticator.getAccessToken()).thenReturn(Mono.just("mock_token"));

        bulkheadManager = new BrokerBulkheadManager();
        objectMapper = new ObjectMapper();

        service = new ShoonyaHistoricalDataService(
            config,
            authenticator,
            bulkheadManager,
            WebClient.builder(),
            objectMapper
        );
    }

    @Test
    void testMockCandleGenerationWhenDisabled() {
        StepVerifier.create(service.fetchHistoricalCandles("NSE:RELIANCE", "NSE", "2885", "1", 50))
            .assertNext(candles -> {
                assertEquals(50, candles.size());
                assertEquals("NSE:RELIANCE", candles.get(0).symbol());
                assertEquals("1", candles.get(0).timeframe());
                assertTrue(candles.get(49).timestamp().isAfter(candles.get(0).timestamp()));
            })
            .verifyComplete();
    }

    @Test
    void testSequentialWarmupThrottling() {
        var req1 = new ShoonyaHistoricalDataService.HistoricalWarmupRequest("NSE:RELIANCE", "NSE", "2885", "1", 10);
        var req2 = new ShoonyaHistoricalDataService.HistoricalWarmupRequest("NSE:TCS", "NSE", "11536", "1", 10);

        StepVerifier.create(service.warmupSequentially(List.of(req1, req2)))
            .assertNext(res -> {
                assertEquals("NSE:RELIANCE", res.symbol());
                assertTrue(res.success());
                assertEquals(10, res.candles().size());
            })
            .assertNext(res -> {
                assertEquals("NSE:TCS", res.symbol());
                assertTrue(res.success());
                assertEquals(10, res.candles().size());
            })
            .verifyComplete();
    }
}
