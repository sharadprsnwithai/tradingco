package com.tradingbot.marketdata;

import com.tradingbot.model.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CircularCandleBufferTest {

    private CircularCandleBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new CircularCandleBuffer(5); // capacity = 5 for test
    }

    @Test
    void testBoundedCapacityAndEviction() {
        assertTrue(buffer.isEmpty());
        assertEquals(5, buffer.capacity());

        Instant baseTime = Instant.parse("2024-12-18T09:15:00Z");

        for (int i = 1; i <= 7; i++) {
            buffer.add(new Candle(
                "NSE:RELIANCE",
                "1",
                baseTime.plusSeconds(i * 60L),
                BigDecimal.valueOf(100 + i),
                BigDecimal.valueOf(105 + i),
                BigDecimal.valueOf(95 + i),
                BigDecimal.valueOf(102 + i),
                1000L * i
            ));
        }

        assertEquals(5, buffer.size());
        assertFalse(buffer.isEmpty());

        List<Candle> all = buffer.getCandles();
        assertEquals(5, all.size());
        // Oldest 2 should have been evicted (i=1,2), so remaining should be i=3,4,5,6,7
        assertEquals(BigDecimal.valueOf(103), all.get(0).open());
        assertEquals(BigDecimal.valueOf(107), all.get(4).open());

        // Test getLast(count)
        List<Candle> last3 = buffer.getLast(3);
        assertEquals(3, last3.size());
        assertEquals(BigDecimal.valueOf(105), last3.get(0).open());
        assertEquals(BigDecimal.valueOf(107), last3.get(2).open());

        // Test getClosePrices
        double[] closes = buffer.getClosePrices();
        assertEquals(5, closes.length);
        assertEquals(105.0, closes[0]);
        assertEquals(109.0, closes[4]);
    }

    @Test
    void testUpdateLast() {
        Instant time = Instant.parse("2024-12-18T09:15:00Z");
        Candle candle1 = new Candle("NSE:RELIANCE", "1", time, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(95), BigDecimal.valueOf(102), 1000L);
        buffer.add(candle1);

        assertEquals(1, buffer.size());
        assertEquals(BigDecimal.valueOf(102), buffer.getLast().orElseThrow().close());

        Candle updated = new Candle("NSE:RELIANCE", "1", time, BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(95), BigDecimal.valueOf(108), 1500L);
        buffer.updateLast(updated);

        assertEquals(1, buffer.size());
        assertEquals(BigDecimal.valueOf(108), buffer.getLast().orElseThrow().close());
        assertEquals(BigDecimal.valueOf(110), buffer.getLast().orElseThrow().high());
    }
}
