package com.tradingbot.marketdata;

import com.tradingbot.model.Candle;
import com.tradingbot.model.Tick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandleAggregatorTest {

    private CandleAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new CandleAggregator();
    }

    @Test
    void testIntraMinuteAggregationAndRollover() {
        List<Candle> closedCandles = new ArrayList<>();
        aggregator.getCandleStream("NSE:RELIANCE", "1").subscribe(closedCandles::add);

        List<Tick> receivedTicks = new ArrayList<>();
        aggregator.getTickStream("NSE:RELIANCE").subscribe(receivedTicks::add);

        Instant baseTime = Instant.parse("2024-12-18T09:15:10Z");

        // Tick 1 at 09:15:10 (LTP = 2500)
        aggregator.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2500.00"))
            .volume(100L)
            .timestamp(baseTime)
            .build());

        // Tick 2 at 09:15:30 (LTP = 2520 - High)
        aggregator.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2520.00"))
            .volume(50L)
            .timestamp(baseTime.plusSeconds(20))
            .build());

        // Tick 3 at 09:15:50 (LTP = 2490 - Low, Close)
        aggregator.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2490.00"))
            .volume(75L)
            .timestamp(baseTime.plusSeconds(40))
            .build());

        assertEquals(3, receivedTicks.size());
        assertEquals(0, closedCandles.size()); // Still within 09:15 minute

        // Tick 4 at 09:16:05 (triggers 09:15 closure)
        aggregator.onTick(Tick.builder()
            .symbol("NSE:RELIANCE")
            .ltp(new BigDecimal("2495.00"))
            .volume(200L)
            .timestamp(baseTime.plusSeconds(55)) // 09:16:05
            .build());

        assertEquals(4, receivedTicks.size());
        assertEquals(1, closedCandles.size());

        Candle candle1 = closedCandles.get(0);
        assertEquals("NSE:RELIANCE", candle1.symbol());
        assertEquals("1", candle1.timeframe());
        assertEquals(new BigDecimal("2500.00"), candle1.open());
        assertEquals(new BigDecimal("2520.00"), candle1.high());
        assertEquals(new BigDecimal("2490.00"), candle1.low());
        assertEquals(new BigDecimal("2490.00"), candle1.close());
        assertEquals(225L, candle1.volume());
    }

    @Test
    void testHierarchicalHigherTimeframeAggregation() {
        List<Candle> closed3mCandles = new ArrayList<>();
        aggregator.getCandleStream("NSE:RELIANCE", "3").subscribe(closed3mCandles::add);

        // Feed ticks spanning 3 distinct minutes (09:15, 09:16, 09:17)
        Instant t0 = Instant.parse("2024-12-18T09:15:05Z");
        Instant t1 = Instant.parse("2024-12-18T09:16:05Z");
        Instant t2 = Instant.parse("2024-12-18T09:17:05Z");
        Instant t3 = Instant.parse("2024-12-18T09:18:05Z");

        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2500")).volume(100).timestamp(t0).build());
        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2530")).volume(100).timestamp(t1).build());
        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2480")).volume(100).timestamp(t2).build());
        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2510")).volume(100).timestamp(t3).build());

        // At t3 (09:18:05), the 3-minute bucket [09:15-09:18) must have closed
        assertEquals(1, closed3mCandles.size());
        Candle c3m = closed3mCandles.get(0);
        assertEquals("3", c3m.timeframe());
        assertEquals(new BigDecimal("2500"), c3m.open());
        assertEquals(new BigDecimal("2530"), c3m.high());
        assertEquals(new BigDecimal("2480"), c3m.low());
        assertEquals(new BigDecimal("2480"), c3m.close());
    }

    @Test
    void testFlatCandleGapFilling() {
        List<Candle> closed1mCandles = new ArrayList<>();
        aggregator.getCandleStream("NSE:RELIANCE", "1").subscribe(closed1mCandles::add);

        Instant t0 = Instant.parse("2024-12-18T09:15:10Z");
        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2500")).volume(100).timestamp(t0).build());

        // Skip 2 minutes (09:16 and 09:17 have no ticks) -> tick arrives at 09:18:05
        Instant t3 = Instant.parse("2024-12-18T09:18:05Z");
        aggregator.onTick(Tick.builder().symbol("NSE:RELIANCE").ltp(new BigDecimal("2510")).volume(100).timestamp(t3).build());

        // Should have closed 09:15 bar + filled 09:16 flat bar + filled 09:17 flat bar = 3 candles
        assertEquals(3, closed1mCandles.size());
        Candle flat1 = closed1mCandles.get(1);
        assertEquals(new BigDecimal("2500"), flat1.open());
        assertEquals(new BigDecimal("2500"), flat1.close());
        assertEquals(0L, flat1.volume());

        Candle flat2 = closed1mCandles.get(2);
        assertEquals(new BigDecimal("2500"), flat2.open());
        assertEquals(0L, flat2.volume());
    }
}
