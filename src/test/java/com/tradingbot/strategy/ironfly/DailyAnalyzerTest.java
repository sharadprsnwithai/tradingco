package com.tradingbot.strategy.ironfly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyAnalyzerTest {

    private DailyAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        // profitTargetPct=4, stopLossPct=8, expiryGuardDays=4, decayThresholdPct=70, shortLegLossPct=70, hedgeProfitPct=50
        analyzer = new DailyAnalyzer(4.0, 8.0, 4, 70.0, 70.0, 50.0);
    }

    @Test
    void testProfitTargetExit() {
        // Spot = 24500, lotSize = 25 -> margin = 24500 * 25 * 0.25 = 153125.
        // 4% profit target = 6125.
        // Let short call make 3500 profit, short put make 3000 profit -> total MTM = 6500 (> 6125).
        OptionLeg shortCall = new OptionLeg("NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), new BigDecimal("60.00"), 0.5, 25); // PnL = (200 - 60) * 25 = 3500
        OptionLeg shortPut = new OptionLeg("NIFTY26SEP24500PE", 24500, OptionType.PE, true,
            new BigDecimal("200.00"), new BigDecimal("80.00"), 0.5, 25); // PnL = (200 - 80) * 25 = 3000
        OptionLeg longCall = new OptionLeg("NIFTY26SEP24900CE", 24900, OptionType.CE, false,
            new BigDecimal("50.00"), new BigDecimal("50.00"), 0.2, 25);
        OptionLeg longPut = new OptionLeg("NIFTY26SEP24100PE", 24100, OptionType.PE, false,
            new BigDecimal("50.00"), new BigDecimal("50.00"), 0.2, 25);

        IronFlyPosition position = new IronFlyPosition(
            "NIFTY", shortCall, shortPut, longCall, longPut,
            new BigDecimal("24500.00"), new BigDecimal("300.00"), new BigDecimal("300.00"),
            25, IronFlyStatus.TRACKING, Instant.now(), null, List.of()
        );

        DailyAnalyzer.EvaluationResult result = analyzer.evaluate(position, 10);
        assertTrue(result.isExit());
        assertEquals(DailyAnalyzer.Action.FULL_EXIT_TARGET, result.action());
    }

    @Test
    void testExpiryGuardExit() {
        IronFlyPosition position = new IronFlyPosition(
            "NIFTY", null, null, null, null,
            new BigDecimal("24500.00"), new BigDecimal("300.00"), new BigDecimal("300.00"),
            25, IronFlyStatus.TRACKING, Instant.now(), null, List.of()
        );

        // Days to expiry = 3 (< 4 expiry guard days)
        DailyAnalyzer.EvaluationResult result = analyzer.evaluate(position, 3);
        assertTrue(result.isExit());
        assertEquals(DailyAnalyzer.Action.FULL_EXIT_EXPIRY, result.action());
    }

    @Test
    void testShortCallDecayAdjustmentTriggered() {
        // Short call decayed 75% (entry 200 -> now 50) >= 70% threshold
        OptionLeg shortCall = new OptionLeg("NIFTY26SEP24500CE", 24500, OptionType.CE, true,
            new BigDecimal("200.00"), new BigDecimal("50.00"), 0.5, 25); // decay = (200-50)/200 * 100 = 75%
        OptionLeg shortPut = new OptionLeg("NIFTY26SEP24500PE", 24500, OptionType.PE, true,
            new BigDecimal("200.00"), new BigDecimal("180.00"), 0.5, 25); // decay = 10%
        OptionLeg longCall = new OptionLeg("NIFTY26SEP24900CE", 24900, OptionType.CE, false,
            new BigDecimal("50.00"), new BigDecimal("30.00"), 0.2, 25);
        OptionLeg longPut = new OptionLeg("NIFTY26SEP24100PE", 24100, OptionType.PE, false,
            new BigDecimal("50.00"), new BigDecimal("45.00"), 0.2, 25);

        IronFlyPosition position = new IronFlyPosition(
            "NIFTY", shortCall, shortPut, longCall, longPut,
            new BigDecimal("24500.00"), new BigDecimal("300.00"), new BigDecimal("300.00"),
            25, IronFlyStatus.TRACKING, Instant.now(), null, List.of()
        );

        DailyAnalyzer.EvaluationResult result = analyzer.evaluate(position, 10);
        assertTrue(result.isAdjust());
        assertEquals(DailyAnalyzer.Action.ADJUST_CALL_SIDE, result.action());
    }
}
