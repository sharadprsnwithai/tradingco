package com.tradingbot.strategy.ironfly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.adapter.kite.KiteAuthenticator;
import com.tradingbot.adapter.kite.KiteConfig;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KiteOptionChainProviderTest {

    private InstrumentMasterService instrumentMaster;
    private KiteConfig kiteConfig;
    private KiteAuthenticator kiteAuthenticator;
    private BrokerAdapterRegistry brokerRegistry;
    private ObjectMapper objectMapper;
    private final List<ClientRequest> recordedRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String dbPath = "build/tmp/test-kite-option-chain.db";
        File f = new File(dbPath);
        if (f.exists()) f.delete();

        instrumentMaster = new InstrumentMasterService(dbPath);
        instrumentMaster.initSchema();

        kiteConfig = new KiteConfig();
        kiteConfig.setEnabled(true);
        kiteConfig.setApiKey("test_key");
        kiteConfig.setAccessToken("test_token");

        objectMapper = new ObjectMapper();
        kiteAuthenticator = new KiteAuthenticator(kiteConfig, objectMapper);
        brokerRegistry = new BrokerAdapterRegistry(List.of());
        recordedRequests.clear();
    }

    private WebClient.Builder createMockWebClientBuilder(String jsonResponse) {
        ExchangeFunction exchangeFunction = request -> {
            recordedRequests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(jsonResponse)
                .build());
        };
        return WebClient.builder().exchangeFunction(exchangeFunction);
    }

    @Test
    void testGetOptionChainSuccessfulFetchAndUriConstruction() {
        // Insert sample instruments into master
        Instrument callInst = Instrument.builder()
            .canonicalSymbol("NFO:NIFTY24DEC24000CE")
            .exchange("NFO")
            .tradingSymbol("NIFTY24DEC24000CE")
            .name("NIFTY")
            .expiry("2024-12-26")
            .strike(new BigDecimal("24000"))
            .instrumentType("CE")
            .lotSize(25)
            .tickSize(new BigDecimal("0.05"))
            .build();

        Instrument putInst = Instrument.builder()
            .canonicalSymbol("NFO:NIFTY24DEC24000PE")
            .exchange("NFO")
            .tradingSymbol("NIFTY24DEC24000PE")
            .name("NIFTY")
            .expiry("2024-12-26")
            .strike(new BigDecimal("24000"))
            .instrumentType("PE")
            .lotSize(25)
            .tickSize(new BigDecimal("0.05"))
            .build();

        instrumentMaster.saveInstruments(List.of(callInst, putInst)).block();

        String kiteResponse = """
            {
                "status": "success",
                "data": {
                    "NFO:NIFTY24DEC24000CE": { "last_price": 180.50 },
                    "NFO:NIFTY24DEC24000PE": { "last_price": 120.25 },
                    "NSE:NIFTY 50": { "last_price": 24050.0 }
                }
            }
            """;

        WebClient.Builder builder = createMockWebClientBuilder(kiteResponse);
        KiteOptionChainProvider provider = new KiteOptionChainProvider(
            brokerRegistry, instrumentMaster, kiteConfig, kiteAuthenticator, builder, objectMapper
        );

        StepVerifier.create(provider.getOptionChain("NIFTY 50", "2024-12-26"))
            .assertNext(chain -> {
                assertNotNull(chain);
                assertFalse(chain.isEmpty());
                assertEquals("NIFTY", chain.underlying());
                assertEquals("2024-12-26", chain.expiry());

                StrikeQuote ce = chain.getCall(24000);
                assertNotNull(ce);
                assertEquals(0, ce.ltp().compareTo(BigDecimal.valueOf(180.50)));

                StrikeQuote pe = chain.getPut(24000);
                assertNotNull(pe);
                assertEquals(0, pe.ltp().compareTo(BigDecimal.valueOf(120.25)));
            })
            .verifyComplete();

        // Verify request URI does NOT have double-encoded percent signs (%25)
        assertFalse(recordedRequests.isEmpty());
        for (ClientRequest req : recordedRequests) {
            String uriStr = req.url().toString();
            assertFalse(uriStr.contains("%25"), "URI should not contain double-encoded %25: " + uriStr);
        }
    }

    @Test
    void testGetSpotPriceMapping() {
        String kiteResponse = """
            {
                "status": "success",
                "data": {
                    "NSE:NIFTY 50": { "last_price": 24123.45 },
                    "NSE:NIFTY BANK": { "last_price": 51200.00 }
                }
            }
            """;

        WebClient.Builder builder = createMockWebClientBuilder(kiteResponse);
        KiteOptionChainProvider provider = new KiteOptionChainProvider(
            brokerRegistry, instrumentMaster, kiteConfig, kiteAuthenticator, builder, objectMapper
        );

        StepVerifier.create(provider.getSpotPrice("NIFTY"))
            .assertNext(spot -> assertEquals(24123.45, spot, 0.001))
            .verifyComplete();

        StepVerifier.create(provider.getSpotPrice("BANKNIFTY"))
            .assertNext(spot -> assertEquals(51200.00, spot, 0.001))
            .verifyComplete();
    }

    @Test
    void testEmptyChainWhenNoInstrumentsInDb() {
        String kiteResponse = """
            {
                "status": "success",
                "data": {}
            }
            """;

        WebClient.Builder builder = createMockWebClientBuilder(kiteResponse);
        KiteOptionChainProvider provider = new KiteOptionChainProvider(
            brokerRegistry, instrumentMaster, kiteConfig, kiteAuthenticator, builder, objectMapper
        );

        StepVerifier.create(provider.getOptionChain("NIFTY", "2024-12-26"))
            .assertNext(chain -> {
                assertNotNull(chain);
                assertTrue(chain.isEmpty());
            })
            .verifyComplete();
    }

    @Test
    void testFetchNiftyOptionChainFromProductionInstrumentDb() {
        File prodDb = new File("data/instruments.db");
        if (!prodDb.exists()) {
            return; // Skip if database is not present in environment
        }

        InstrumentMasterService prodMaster = new InstrumentMasterService("data/instruments.db");
        prodMaster.initSchema();

        // Dynamically mock response for any requested symbol
        ExchangeFunction dynamicExchange = request -> {
            recordedRequests.add(request);
            String url = request.url().toString();
            StringBuilder dataJson = new StringBuilder("{");

            // If spot index requested
            if (url.contains("NSE%3ANIFTY") || url.contains("NSE:NIFTY")) {
                dataJson.append("\"NSE:NIFTY 50\": { \"last_price\": 24500.0 }");
            }

            // Mock LTPs for option contracts (e.g. 150.0 for ATM, fading OTM)
            for (String queryPart : url.split("&")) {
                if (queryPart.startsWith("i=") || queryPart.contains("?i=")) {
                    String sym = queryPart.substring(queryPart.indexOf("i=") + 2).replace("%3A", ":");
                    if (dataJson.length() > 1) dataJson.append(",");
                    dataJson.append("\"").append(sym).append("\": { \"last_price\": 125.50 }");
                }
            }
            dataJson.append("}");

            String body = "{\"status\": \"success\", \"data\": " + dataJson + "}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
        };

        WebClient.Builder dynamicBuilder = WebClient.builder().exchangeFunction(dynamicExchange);
        KiteOptionChainProvider provider = new KiteOptionChainProvider(
            brokerRegistry, prodMaster, kiteConfig, kiteAuthenticator, dynamicBuilder, objectMapper
        );

        // Fetch NIFTY option chain for nearest expiry
        OptionChain chain = provider.getOptionChain("NIFTY 50", null).block();

        assertNotNull(chain, "OptionChain should not be null");
        assertFalse(chain.isEmpty(), "OptionChain should contain NIFTY contracts");
        assertEquals("NIFTY", chain.underlying());
        assertNotNull(chain.expiry(), "Expiry should be resolved");
        assertFalse(chain.calls().isEmpty(), "Calls map should not be empty");
        assertFalse(chain.puts().isEmpty(), "Puts map should not be empty");

        // Verify spot price
        Double spot = provider.getSpotPrice("NIFTY").block();
        assertNotNull(spot);
        assertEquals(24500.0, spot, 0.01);

        // Verify ATM strikes exist
        int sampleStrike = chain.calls().keySet().iterator().next();
        StrikeQuote call = chain.getCall(sampleStrike);
        StrikeQuote put = chain.getPut(sampleStrike);

        assertNotNull(call, "Call quote should exist for strike " + sampleStrike);
        assertNotNull(put, "Put quote should exist for strike " + sampleStrike);
        assertEquals(OptionType.CE, call.optionType());
        assertEquals(OptionType.PE, put.optionType());
        assertTrue(call.ltp().doubleValue() > 0, "Call LTP should be positive");
        assertTrue(put.ltp().doubleValue() > 0, "Put LTP should be positive");
    }
}
