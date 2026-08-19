package com.tradingbot.strategy.impl;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Signal;
import com.tradingbot.model.Tick;
import com.tradingbot.model.enums.SignalType;
import com.tradingbot.strategy.ScheduledEvent;
import com.tradingbot.strategy.StrategyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VandeBharatStrategyTest {

    private VandeBharatStrategy strategy;
    private List<Signal> emittedSignals;
    private List<Candle> mockBufferCandles;

    @BeforeEach
    void setUp() {
        emittedSignals = new ArrayList<>();
        mockBufferCandles = new ArrayList<>();

        strategy = new VandeBharatStrategy(
            "VB_TEST",
            "KITE_123",
            "NSE:RELIANCE",
            10
        );

        StrategyContext context = new StrategyContext() {
            @Override public String getStrategyId() { return "VB_TEST"; }
            @Override public String getAssignedAccountId() { return "KITE_123"; }
            @Override public void emitSignal(Signal signal) { emittedSignals.add(signal); }
            @Override public Optional<Candle> getLastCandle(String symbol, String timeframe) {
                return mockBufferCandles.isEmpty() ? Optional.empty() : Optional.of(mockBufferCandles.get(mockBufferCandles.size() - 1));
            }
            @Override public List<Candle> getHistoricalCandles(String symbol, String timeframe, int count) {
                return mockBufferCandles;
            }
            @Override public double[] getClosePrices(String symbol, String timeframe) {
                return mockBufferCandles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            }
            @Override public Instant now() { return Instant.now(); }
        };

        strategy.init(context);
    }

    @Test
    void testCompleteVandeBharatTradeLifecycle() {
        Instant t0 = Instant.parse("2024-12-18T09:15:00Z");

        // Seed candle to establish PDH (3000 * 1.01 = 3030) and PDL (3000 * 0.99 = 2970)
        Candle seed = new Candle("NSE:RELIANCE", "5", t0, new BigDecimal("3000"), new BigDecimal("3010"), new BigDecimal("2990"), new BigDecimal("3000"), 1000L);
        strategy.onCandle(seed);

        VandeBharatStrategy.StockState state = strategy.getState("NSE:RELIANCE");
        assertNotNull(state.pdh);
        assertEquals(new BigDecimal("3030.00"), state.pdh);

        // Candle 1: Breakout Candle above PDH (Close: 3040 <= 3030 * 1.02 = 3090.6)
        Instant t1 = t0.plusSeconds(300);
        Candle breakout = new Candle("NSE:RELIANCE", "5", t1, new BigDecimal("3020"), new BigDecimal("3050"), new BigDecimal("3015"), new BigDecimal("3040"), 5000L);
        strategy.onCandle(breakout);

        assertEquals(VandeBharatStrategy.Direction.LONG, state.direction);
        assertNotNull(state.breakoutCandle);
        assertNull(state.insideCandle);

        // Candle 2: Inside Candle (High <= 3050, Low >= 3015, Volume <= 5000)
        Instant t2 = t1.plusSeconds(300);
        Candle inside = new Candle("NSE:RELIANCE", "5", t2, new BigDecimal("3035"), new BigDecimal("3045"), new BigDecimal("3025"), new BigDecimal("3030"), 2000L);
        strategy.onCandle(inside);

        assertNotNull(state.insideCandle);
        assertEquals(new BigDecimal("3045"), state.insideCandle.high());
        assertEquals(new BigDecimal("3025"), state.insideCandle.low());

        // Candle 3: Entry Trigger Candle (Close > 3045, Volume > 2000)
        Instant t3 = t2.plusSeconds(300);
        Candle entryTrigger = new Candle("NSE:RELIANCE", "5", t3, new BigDecimal("3030"), new BigDecimal("3060"), new BigDecimal("3028"), new BigDecimal("3055"), 4000L);
        strategy.onCandle(entryTrigger);

        assertEquals(VandeBharatStrategy.TradePosition.IN_TRADE, state.position);
        assertEquals(1, emittedSignals.size());
        Signal entrySignal = emittedSignals.get(0);
        assertEquals(SignalType.ENTRY_LONG, entrySignal.signalType());
        assertEquals(10, entrySignal.quantity());
        assertEquals(new BigDecimal("3055"), entrySignal.price());
        assertEquals(new BigDecimal("3025"), entrySignal.triggerPrice()); // Initial SL = inside candle low

        // Tick 1: Price rallies to 3085 (Target 1:2 RR: Entry 3045 + 2 * (3045 - 3025 = 20) = 3085)
        strategy.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("3085.00"))
            .timestamp(t3.plusSeconds(30))
            .build());

        assertEquals(2, emittedSignals.size());
        Signal partialExit = emittedSignals.get(1);
        assertEquals(SignalType.EXIT_PARTIAL_LONG, partialExit.signalType());
        assertEquals(5, partialExit.quantity()); // 50% of 10 = 5 booked
        assertTrue(state.partialExitBooked);

        // Trailing SL check: Highest price = 3085, Stop distance = 20 -> Trailing SL = 3065
        assertEquals(new BigDecimal("3065.00"), state.trailingStopLoss);

        // Tick 2: Price drops to 3060 (triggers Trailing SL exit at 3065)
        strategy.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("3060.00"))
            .timestamp(t3.plusSeconds(60))
            .build());

        assertEquals(3, emittedSignals.size());
        Signal finalExit = emittedSignals.get(2);
        assertEquals(SignalType.EXIT_LONG, finalExit.signalType());
        assertEquals(5, finalExit.quantity()); // remaining 5
        assertEquals(VandeBharatStrategy.TradePosition.FLAT, state.position);
    }

    @Test
    void testScheduledIntradaySquareOff() {
        VandeBharatStrategy.StockState state = strategy.getState("NSE:RELIANCE");
        state.position = VandeBharatStrategy.TradePosition.IN_TRADE;
        state.direction = VandeBharatStrategy.Direction.LONG;
        state.remainingQuantity = 10;
        state.highestPrice = new BigDecimal("3050");

        strategy.onSchedule(ScheduledEvent.of(ScheduledEvent.INTRADAY_SQUARE_OFF));

        assertEquals(1, emittedSignals.size());
        assertEquals(SignalType.EXIT_LONG, emittedSignals.get(0).signalType());
        assertEquals("VB_EXIT_EOD_SQUARE_OFF_15:14", emittedSignals.get(0).tag());
        assertEquals(VandeBharatStrategy.TradePosition.FLAT, state.position);
    }

    @Test
    void test0925PreMarketOiScanTop5Selection() {
        List<VandeBharatStrategy.OiScanResult> scanResults = List.of(
            VandeBharatStrategy.OiScanResult.of("NSE:RELIANCE", 500000, 750000),   // Total: 1,250,000 (#1)
            VandeBharatStrategy.OiScanResult.of("NSE:TCS", 200000, 300000),        // Total: 500,000 (#4)
            VandeBharatStrategy.OiScanResult.of("NSE:INFY", 400000, 500000),       // Total: 900,000 (#3)
            VandeBharatStrategy.OiScanResult.of("NSE:HDFCBANK", 600000, 600000),   // Total: 1,200,000 (#2)
            VandeBharatStrategy.OiScanResult.of("NSE:ICICIBANK", 150000, 200000),  // Total: 350,000 (#5)
            VandeBharatStrategy.OiScanResult.of("NSE:SBIN", 100000, 100000),       // Total: 200,000 (#6 - excluded)
            VandeBharatStrategy.OiScanResult.of("NSE:TATAMOTORS", 50000, 50000)    // Total: 100,000 (#7 - excluded)
        );

        List<String> top5 = strategy.updateWatchlistFromOiScan(scanResults);

        assertEquals(5, top5.size());
        assertEquals("NSE:RELIANCE", top5.get(0));
        assertEquals("NSE:HDFCBANK", top5.get(1));
        assertEquals("NSE:INFY", top5.get(2));
        assertEquals("NSE:TCS", top5.get(3));
        assertEquals("NSE:ICICIBANK", top5.get(4));

        assertFalse(top5.contains("NSE:SBIN"));
        assertFalse(top5.contains("NSE:TATAMOTORS"));

        // Verify strategy subscribed symbols updated
        assertEquals(top5, strategy.getSubscribedSymbols());
        assertNotNull(strategy.getState("NSE:RELIANCE"));
        assertNotNull(strategy.getState("NSE:HDFCBANK"));
    }
}
