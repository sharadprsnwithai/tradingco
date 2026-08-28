package com.tradingbot.strategy.ironfly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Handles strike selection for Iron Fly adjustments.
 * Selects new short and long strikes based on delta targets and credit constraints.
 */
@Component
public class AdjustmentHandler {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentHandler.class);

    private final double targetDelta;
    private final double hedgeCreditBuffer;

    public AdjustmentHandler(
        @Value("${ironfly.target-delta:${ironfly.targetDelta:0.25}}") double targetDelta,
        @Value("${ironfly.hedge-credit-buffer:${ironfly.hedgeCreditBuffer:0.5}}") double hedgeCreditBuffer
    ) {
        this.targetDelta = targetDelta;
        this.hedgeCreditBuffer = hedgeCreditBuffer;
    }

    /**
     * Selects new strikes for an adjustment on the given side.
     *
     * @param chain            the current option chain
     * @param side             which side to adjust (CALL or PUT)
     * @param currentSpot      the current spot price
     * @param currentNetCredit the current net credit of the position
     * @param lotSize          the lot size for the underlying
     * @return adjustment strike selection result
     */
    public AdjustmentStrikeSelection selectStrikes(
        OptionChain chain,
        AdjustmentSide side,
        double currentSpot,
        BigDecimal currentNetCredit,
        int lotSize
    ) {
        OptionType targetOptionType = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        Map<Integer, StrikeQuote> quotes = (side == AdjustmentSide.CALL) ? chain.calls() : chain.puts();

        // Find the strike closest to target delta (matching absolute delta)
        Optional<StrikeQuote> targetStrike = quotes.values().stream()
            .filter(q -> Math.abs(Math.abs(q.delta()) - targetDelta) < 0.15)
            .min(Comparator.comparingDouble(q -> Math.abs(Math.abs(q.delta()) - targetDelta)));

        if (targetStrike.isEmpty()) {
            log.warn("[IronFly] No strike found near target delta {} for {} side, falling back to OTM",
                targetDelta, side);
            return selectByMoneyness(chain, side, currentSpot, currentNetCredit, lotSize);
        }

        int newShortStrike = targetStrike.get().strike();

        // Select hedge strike to maintain positive net credit
        int newLongStrike = selectHedgeStrike(chain, side, newShortStrike, currentNetCredit, lotSize);

        BigDecimal shortPremium = targetStrike.get().ltp();
        StrikeQuote hedgeQuote = chain.getQuote(newLongStrike, targetOptionType);
        BigDecimal hedgePremium = hedgeQuote != null ? hedgeQuote.ltp() : BigDecimal.ZERO;

        BigDecimal creditDelta = shortPremium.subtract(hedgePremium);

        log.info("[IronFly] {} adjustment: new short {} @{} (delta {:.2f}), new long {} @{}, credit delta ₹{}",
            side, targetOptionType, newShortStrike, targetStrike.get().delta(),
            targetOptionType, newLongStrike, creditDelta);

        return new AdjustmentStrikeSelection(newShortStrike, newLongStrike, shortPremium, hedgePremium, creditDelta);
    }

    private int selectHedgeStrike(OptionChain chain, AdjustmentSide side,
                                  int shortStrike, BigDecimal currentNetCredit, int lotSize) {
        OptionType type = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        int step = (side == AdjustmentSide.CALL) ? 50 : -50;

        // Walk away from ATM to find hedge where credit stays positive
        for (int i = 1; i <= 10; i++) {
            int candidateStrike = shortStrike + (step * i);
            StrikeQuote quote = chain.getQuote(candidateStrike, type);
            if (quote != null) {
                BigDecimal hedgeCost = quote.ltp();
                // Check if net credit would remain positive
                if (currentNetCredit.subtract(hedgeCost).compareTo(BigDecimal.ZERO) > 0) {
                    return candidateStrike;
                }
            }
        }

        // Fallback: 2 strikes away
        return shortStrike + (step * 2);
    }

    private AdjustmentStrikeSelection selectByMoneyness(OptionChain chain,
                                                        AdjustmentSide side, double spot,
                                                        BigDecimal currentNetCredit, int lotSize) {
        OptionType type = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        double offset = spot * 0.02;

        int targetStrike;
        if (type == OptionType.CE) {
            targetStrike = (int) Math.round((spot + offset) / 50.0) * 50;
        } else {
            targetStrike = (int) Math.round((spot - offset) / 50.0) * 50;
        }

        StrikeQuote quote = chain.getQuote(targetStrike, type);
        BigDecimal shortPremium = quote != null ? quote.ltp() : BigDecimal.ZERO;

        int hedgeStrike = targetStrike + ((type == OptionType.CE) ? 50 : -50);
        StrikeQuote hedgeQuote = chain.getQuote(hedgeStrike, type);
        BigDecimal hedgePremium = hedgeQuote != null ? hedgeQuote.ltp() : BigDecimal.ZERO;

        BigDecimal creditDelta = shortPremium.subtract(hedgePremium);

        return new AdjustmentStrikeSelection(targetStrike, hedgeStrike, shortPremium, hedgePremium, creditDelta);
    }

    public record AdjustmentStrikeSelection(
        int newShortStrike,
        int newLongStrike,
        BigDecimal shortPremium,
        BigDecimal hedgePremium,
        BigDecimal creditDelta
    ) {}
}
