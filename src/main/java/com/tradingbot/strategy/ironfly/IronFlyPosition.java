package com.tradingbot.strategy.ironfly;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a complete Iron Fly position with all four legs and lifecycle state.
 */
public record IronFlyPosition(
    String underlying,
    OptionLeg shortCall,
    OptionLeg shortPut,
    OptionLeg longCallHedge,
    OptionLeg longPutHedge,
    BigDecimal entrySpotPrice,
    BigDecimal netCredit,
    BigDecimal originalCredit,
    int totalLotSize,
    IronFlyStatus status,
    Instant createdAt,
    Instant closedAt,
    List<AdjustmentRecord> adjustmentHistory
) {
    public IronFlyPosition {
        if (adjustmentHistory == null) adjustmentHistory = Collections.emptyList();
    }

    /**
     * Calculates the total MTM P&L across all four legs.
     */
    public BigDecimal getTotalMtm() {
        BigDecimal shortCallPnl = nullSafe(shortCall).getMtmPnl();
        BigDecimal shortPutPnl = nullSafe(shortPut).getMtmPnl();
        BigDecimal longCallPnl = nullSafe(longCallHedge).getMtmPnl();
        BigDecimal longPutPnl = nullSafe(longPutHedge).getMtmPnl();
        return shortCallPnl.add(shortPutPnl).add(longCallPnl).add(longPutPnl);
    }

    /**
     * Upper breakeven = ATM strike + netCredit.
     */
    public BigDecimal getUpperBreakeven() {
        int atmStrike = shortCall != null ? shortCall.strike() : 0;
        return BigDecimal.valueOf(atmStrike).add(nullSafe(netCredit));
    }

    /**
     * Lower breakeven = ATM strike - netCredit.
     */
    public BigDecimal getLowerBreakeven() {
        int atmStrike = shortPut != null ? shortPut.strike() : 0;
        return BigDecimal.valueOf(atmStrike).subtract(nullSafe(netCredit));
    }

    /**
     * Returns the ATM strike (short call strike, which equals short put strike in an iron fly).
     */
    public int getAtmStrike() {
        return shortCall != null ? shortCall.strike() : 0;
    }

    /**
     * Returns the current net credit including all adjustment deltas.
     */
    public BigDecimal getCurrentNetCredit() {
        BigDecimal delta = adjustmentHistory.stream()
            .map(AdjustmentRecord::creditDelta)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return nullSafe(originalCredit).add(delta);
    }

    /**
     * Returns the number of adjustments applied.
     */
    public int getAdjustmentCount() {
        return adjustmentHistory.size();
    }

    private static BigDecimal nullSafe(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }

    private static OptionLeg nullSafe(OptionLeg leg) {
        return leg != null ? leg : new OptionLeg(null, 0, OptionType.CE, false, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0);
    }
}
