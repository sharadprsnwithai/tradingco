package com.tradingbot.strategy.ironfly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdjustmentHandlerTest {

    private AdjustmentHandler handler;

    @BeforeEach
    void setUp() {
        // targetDelta = 0.25, hedgeCreditBuffer = 0.5
        handler = new AdjustmentHandler(0.25, 0.5);
    }

    @Test
    void testCallSideAdjustmentSelectsCorrectDeltaStrike() {
        StrikeQuote call24600 = new StrikeQuote(
            24600, OptionType.CE,
            new BigDecimal("150.00"), new BigDecimal("149.00"), new BigDecimal("151.00"),
            100000, 5000, 0.40, 0.001, -15.0, 12.0
        );

        StrikeQuote call24800 = new StrikeQuote(
            24800, OptionType.CE,
            new BigDecimal("65.00"), new BigDecimal("64.00"), new BigDecimal("66.00"),
            80000, 3000, 0.26, 0.001, -10.0, 9.0 // Close to 0.25 target delta
        );

        StrikeQuote call24900 = new StrikeQuote(
            24900, OptionType.CE,
            new BigDecimal("35.00"), new BigDecimal("34.00"), new BigDecimal("36.00"),
            60000, 2000, 0.15, 0.001, -7.0, 6.0
        );

        OptionChain chain = new OptionChain(
            "NIFTY", "2026-09-24",
            Map.of(24600, call24600, 24800, call24800, 24900, call24900),
            Map.of()
        );

        AdjustmentHandler.AdjustmentStrikeSelection result = handler.selectStrikes(
            chain, AdjustmentSide.CALL, 24500.0, new BigDecimal("300.00"), 25
        );

        assertNotNull(result);
        assertEquals(24800, result.newShortStrike());
        assertEquals(new BigDecimal("65.00"), result.shortPremium());
    }

    @Test
    void testPutSideAdjustmentSelectsCorrectDeltaStrike() {
        StrikeQuote put24400 = new StrikeQuote(
            24400, OptionType.PE,
            new BigDecimal("140.00"), new BigDecimal("139.00"), new BigDecimal("141.00"),
            100000, 5000, -0.42, 0.001, -15.0, 12.0
        );

        StrikeQuote put24200 = new StrikeQuote(
            24200, OptionType.PE,
            new BigDecimal("60.00"), new BigDecimal("59.00"), new BigDecimal("61.00"),
            80000, 3000, -0.24, 0.001, -10.0, 9.0 // Close to 0.25 target delta
        );

        StrikeQuote put24100 = new StrikeQuote(
            24100, OptionType.PE,
            new BigDecimal("30.00"), new BigDecimal("29.00"), new BigDecimal("31.00"),
            60000, 2000, -0.14, 0.001, -7.0, 6.0
        );

        OptionChain chain = new OptionChain(
            "NIFTY", "2026-09-24",
            Map.of(),
            Map.of(24400, put24400, 24200, put24200, 24100, put24100)
        );

        AdjustmentHandler.AdjustmentStrikeSelection result = handler.selectStrikes(
            chain, AdjustmentSide.PUT, 24500.0, new BigDecimal("300.00"), 25
        );

        assertNotNull(result);
        assertEquals(24200, result.newShortStrike());
        assertEquals(new BigDecimal("60.00"), result.shortPremium());
    }
}
