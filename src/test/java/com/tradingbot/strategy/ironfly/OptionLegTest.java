package com.tradingbot.strategy.ironfly;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OptionLegTest {

    @Test
    void testShortLegDecayCalculation() {
        OptionLeg leg = new OptionLeg(
            "NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), new BigDecimal("60.00"), 0.5, 25
        );

        // Decay = (200 - 60) / 200 * 100 = 70.0%
        assertEquals(70.0, leg.getDecayPercentage(), 0.001);
    }

    @Test
    void testDecayWithZeroOrNullCurrentPriceReturnsZero() {
        OptionLeg legWithZeroPrice = new OptionLeg(
            "NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), BigDecimal.ZERO, 0.5, 25
        );
        assertEquals(0.0, legWithZeroPrice.getDecayPercentage(), 0.001);

        OptionLeg legWithNullPrice = new OptionLeg(
            "NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), null, 0.5, 25
        );
        assertEquals(0.0, legWithNullPrice.getDecayPercentage(), 0.001);
    }

    @Test
    void testLongLegProfitCalculation() {
        OptionLeg hedge = new OptionLeg(
            "NIFTY26SEP24900CE", 24900, OptionType.CE, false,
            new BigDecimal("50.00"), new BigDecimal("75.00"), 0.2, 25
        );

        // Profit = (75 - 50) / 50 * 100 = 50.0%
        assertEquals(50.0, hedge.getProfitPercentage(), 0.001);
    }

    @Test
    void testMtmPnl() {
        OptionLeg shortLeg = new OptionLeg(
            "NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), new BigDecimal("150.00"), 0.5, 25
        );
        // Short leg: (200 - 150) * 25 = 1250 profit
        assertEquals(new BigDecimal("1250.00"), shortLeg.getMtmPnl());

        OptionLeg longLeg = new OptionLeg(
            "NIFTY26SEP24900CE", 24900, OptionType.CE, false,
            new BigDecimal("50.00"), new BigDecimal("80.00"), 0.2, 25
        );
        // Long leg: (80 - 50) * 25 = 750 profit
        assertEquals(new BigDecimal("750.00"), longLeg.getMtmPnl());
    }
}
