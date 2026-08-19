package com.tradingbot.strategy;

import com.tradingbot.marketdata.CandleAggregator;
import com.tradingbot.marketdata.MarketDataHub;
import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrategyEngineTest {

    private MarketDataHub marketDataHub;
    private CandleAggregator candleAggregator;
    private StrategyEngine engine;

    private Sinks.Many<Tick> tickSink;
    private Sinks.Many<Candle> candleSink;

    @BeforeEach
    void setUp() {
        marketDataHub = mock(MarketDataHub.class);
        candleAggregator = new CandleAggregator();

        tickSink = Sinks.many().multicast().directBestEffort();
        candleSink = Sinks.many().multicast().directBestEffort();

        when(marketDataHub.getTickStream()).thenReturn(tickSink.asFlux());
        when(marketDataHub.getCandleStream(any())).thenReturn(candleSink.asFlux());
        when(marketDataHub.subscribe(anyList())).thenReturn(Mono.empty());

        engine = new StrategyEngine(marketDataHub, candleAggregator, List.of());
        engine.start();
    }

    @Test
    void testPluggableStrategyRegistrationAndLifecycle() {
        AtomicBoolean initialized = new AtomicBoolean(false);
        AtomicBoolean destroyed = new AtomicBoolean(false);

        Strategy mockStrategy = new Strategy() {
            private boolean enabled = true;
            @Override public String getStrategyId() { return "TEST_STRAT_01"; }
            @Override public String getAssignedAccountId() { return "ACC_KITE_01"; }
            @Override public List<String> getSubscribedSymbols() { return List.of("NSE:RELIANCE"); }
            @Override public void init(StrategyContext context) { initialized.set(true); }
            @Override public void onTick(Tick tick) {}
            @Override public void onCandle(Candle candle) {}
            @Override public void onSchedule(ScheduledEvent event) {}
            @Override public void destroy() { destroyed.set(true); }
            @Override public boolean isEnabled() { return enabled; }
            @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
        };

        engine.registerStrategy(mockStrategy);

        assertTrue(initialized.get());
        assertEquals(1, engine.getRegisteredStrategies().size());
        assertTrue(engine.getStrategy("TEST_STRAT_01").isPresent());

        engine.unregisterStrategy("TEST_STRAT_01");
        assertTrue(destroyed.get());
        assertEquals(0, engine.getRegisteredStrategies().size());
    }

    @Test
    void testEventDispatchingAndSignalEmission() throws InterruptedException {
        CountDownLatch candleLatch = new CountDownLatch(1);
        CountDownLatch tickLatch = new CountDownLatch(1);
        CountDownLatch scheduleLatch = new CountDownLatch(1);

        Strategy tradingStrategy = new Strategy() {
            private StrategyContext context;
            private boolean enabled = true;

            @Override public String getStrategyId() { return "SIGNAL_STRAT"; }
            @Override public String getAssignedAccountId() { return "ACC_SHOONYA_01"; }
            @Override public List<String> getSubscribedSymbols() { return List.of("NSE:INFY"); }
            @Override public void init(StrategyContext context) { this.context = context; }

            @Override
            public void onTick(Tick tick) {
                tickLatch.countDown();
            }

            @Override
            public void onCandle(Candle candle) {
                candleLatch.countDown();
                // Emit signal on candle
                context.emitSignal(Signal.builder()
                    .strategyId(getStrategyId())
                    .targetAccountId(getAssignedAccountId())
                    .symbol(candle.symbol())
                    .signalType(SignalType.ENTRY_LONG)
                    .quantity(10)
                    .price(candle.close())
                    .tag("TEST_ENTRY")
                    .build());
            }

            @Override
            public void onSchedule(ScheduledEvent event) {
                scheduleLatch.countDown();
            }

            @Override public void destroy() {}
            @Override public boolean isEnabled() { return enabled; }
            @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
        };

        List<Signal> collectedSignals = new ArrayList<>();
        engine.getSignalStream("SIGNAL_STRAT").subscribe(collectedSignals::add);

        engine.registerStrategy(tradingStrategy);

        // Dispatch Tick
        tickSink.tryEmitNext(Tick.builder()
            .symbol("NSE:INFY")
            .ltp(new BigDecimal("1850.00"))
            .timestamp(Instant.now())
            .build());

        // Dispatch Candle
        candleSink.tryEmitNext(new Candle(
            "NSE:INFY", "5", Instant.now(),
            new BigDecimal("1840"), new BigDecimal("1860"),
            new BigDecimal("1835"), new BigDecimal("1855"), 5000L
        ));

        // Dispatch Schedule
        engine.dispatchSchedule(ScheduledEvent.of(ScheduledEvent.MARKET_OPEN));

        assertTrue(tickLatch.await(2, TimeUnit.SECONDS), "Tick should be dispatched to strategy");
        assertTrue(candleLatch.await(2, TimeUnit.SECONDS), "Candle should be dispatched to strategy");
        assertTrue(scheduleLatch.await(2, TimeUnit.SECONDS), "Schedule should be dispatched to strategy");

        // Verify signal was emitted into the stream
        Thread.sleep(100);
        assertEquals(1, collectedSignals.size());
        Signal s = collectedSignals.get(0);
        assertEquals("SIGNAL_STRAT", s.strategyId());
        assertEquals("NSE:INFY", s.symbol());
        assertEquals(SignalType.ENTRY_LONG, s.signalType());
        assertEquals(new BigDecimal("1855"), s.price());
    }
}
