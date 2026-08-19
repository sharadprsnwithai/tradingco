package com.tradingbot.marketdata;

import com.tradingbot.adapter.BrokerAdapter;
import com.tradingbot.adapter.BrokerAdapterRegistry;
import com.tradingbot.instrument.InstrumentMasterService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Instrument;
import com.tradingbot.model.Tick;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketDataHubTest {

    private BrokerAdapterRegistry brokerRegistry;
    private InstrumentMasterService instrumentMaster;
    private CandleAggregator candleAggregator;
    private BrokerAdapter kiteAdapter;
    private BrokerAdapter shoonyaAdapter;

    private Sinks.Many<Tick> kiteTickSink;
    private Sinks.Many<Tick> shoonyaTickSink;

    private MarketDataHub hub;

    @BeforeEach
    void setUp() {
        brokerRegistry = mock(BrokerAdapterRegistry.class);
        instrumentMaster = mock(InstrumentMasterService.class);
        candleAggregator = new CandleAggregator();

        kiteAdapter = mock(BrokerAdapter.class);
        shoonyaAdapter = mock(BrokerAdapter.class);

        when(kiteAdapter.getBrokerId()).thenReturn("ZERODHA");
        when(shoonyaAdapter.getBrokerId()).thenReturn("SHOONYA");

        kiteTickSink = Sinks.many().multicast().directBestEffort();
        shoonyaTickSink = Sinks.many().multicast().directBestEffort();

        when(kiteAdapter.subscribeMarketData(anyList())).thenReturn(kiteTickSink.asFlux());
        when(shoonyaAdapter.subscribeMarketData(anyList())).thenReturn(shoonyaTickSink.asFlux());

        when(brokerRegistry.getByBrokerId("ZERODHA")).thenReturn(Mono.just(kiteAdapter));
        when(brokerRegistry.getByBrokerId("SHOONYA")).thenReturn(Mono.just(shoonyaAdapter));

        when(instrumentMaster.findByCanonicalSymbol(anyString())).thenReturn(Mono.just(
            Instrument.builder().canonicalSymbol("NSE:RELIANCE").kiteToken("738561").shoonyaToken("2885").build()
        ));

        hub = new MarketDataHub(brokerRegistry, instrumentMaster, candleAggregator, 1); // 1-second silence threshold
    }

    @Test
    void testSubscribeAndTickRouting() {
        StepVerifier.create(hub.subscribe(List.of("NSE:RELIANCE")))
            .verifyComplete();

        assertEquals("ZERODHA", hub.getActiveBroker());
        assertFalse(hub.isFailedOver());
        assertEquals(List.of("NSE:RELIANCE"), hub.getActiveSymbols());

        List<Tick> receivedTicks = new java.util.ArrayList<>();
        hub.getTickStream("NSE:RELIANCE").subscribe(receivedTicks::add);

        Tick tick = Tick.builder()
            .brokerId("ZERODHA")
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2500.00"))
            .volume(100L)
            .timestamp(Instant.now())
            .build();

        kiteTickSink.tryEmitNext(tick);

        assertEquals(1, receivedTicks.size());
        assertEquals(new BigDecimal("2500.00"), receivedTicks.get(0).ltp());
    }

    @Test
    void testFailoverOnSilence() throws InterruptedException {
        StepVerifier.create(hub.subscribe(List.of("NSE:RELIANCE")))
            .verifyComplete();

        assertEquals("ZERODHA", hub.getActiveBroker());

        // Simulate silence by manually triggering checkSilence after threshold
        hub.checkSilence(); // Within threshold -> no failover
        assertFalse(hub.isFailedOver());

        // Trigger failover directly
        hub.triggerFailover("Test silence triggered");

        assertTrue(hub.isFailedOver());
        assertEquals("SHOONYA", hub.getActiveBroker());

        // Emit a tick on Shoonya stream and verify it routes through aggregator
        List<Tick> receivedTicks = new java.util.ArrayList<>();
        hub.getTickStream("NSE:RELIANCE").subscribe(receivedTicks::add);

        Tick shoonyaTick = Tick.builder()
            .brokerId("SHOONYA")
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2505.00"))
            .volume(150L)
            .timestamp(Instant.now())
            .build();

        shoonyaTickSink.tryEmitNext(shoonyaTick);

        assertEquals(1, receivedTicks.size());
        assertEquals(new BigDecimal("2505.00"), receivedTicks.get(0).ltp());
    }

    @Test
    void testManualBrokerSwitch() {
        StepVerifier.create(hub.subscribe(List.of("NSE:RELIANCE")))
            .verifyComplete();

        StepVerifier.create(hub.switchBroker("SHOONYA", "Manual test switch"))
            .verifyComplete();

        assertEquals("SHOONYA", hub.getActiveBroker());
        assertTrue(hub.isFailedOver());
    }
}
