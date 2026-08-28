package com.tradingbot.strategy.ironfly;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OptionChainTest {

    @Test
    void testOptionChainSeparatesCallsAndPutsWithoutKeyCollision() {
        int strike = 24500;

        StrikeQuote callQuote = new StrikeQuote(
            strike, OptionType.CE,
            new BigDecimal("220.50"), new BigDecimal("220.00"), new BigDecimal("221.00"),
            100000, 5000, 0.52, 0.001, -15.0, 12.0
        );

        StrikeQuote putQuote = new StrikeQuote(
            strike, OptionType.PE,
            new BigDecimal("195.25"), new BigDecimal("195.00"), new BigDecimal("195.50"),
            120000, 6000, -0.48, 0.001, -14.0, 11.5
        );

        OptionChain chain = new OptionChain(
            "NIFTY", "2026-09-24",
            Map.of(strike, callQuote),
            Map.of(strike, putQuote)
        );

        assertFalse(chain.isEmpty());
        assertEquals(callQuote, chain.getCall(strike));
        assertEquals(putQuote, chain.getPut(strike));
        assertEquals(callQuote, chain.getQuote(strike, OptionType.CE));
        assertEquals(putQuote, chain.getQuote(strike, OptionType.PE));
        assertTrue(chain.getAllStrikes().contains(strike));
    }

    @Test
    void testEmptyOptionChain() {
        OptionChain empty = OptionChain.empty("NIFTY", "2026-09-24");
        assertTrue(empty.isEmpty());
        assertNull(empty.getCall(24500));
        assertNull(empty.getPut(24500));
    }
}
