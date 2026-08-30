package com.tradingbot.strategy.ironfly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
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
        int stepSize = deriveStrikeStep(chain, currentSpot);
        OptionType targetOptionType = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        Map<Integer, StrikeQuote> quotes = (side == AdjustmentSide.CALL) ? chain.calls() : chain.puts();

        // Find the strike closest to target delta (matching absolute delta)
        Optional<StrikeQuote> targetStrike = quotes.values().stream()
            .filter(q -> Math.abs(Math.abs(q.delta()) - targetDelta) < 0.15)
            .min(Comparator.comparingDouble(q -> Math.abs(Math.abs(q.delta()) - targetDelta)));

        if (targetStrike.isEmpty()) {
            log.warn("[IronFly] No strike found near target delta {} for {} side, falling back to OTM",
                targetDelta, side);
            return selectByMoneyness(chain, side, currentSpot, currentNetCredit, lotSize, stepSize);
        }

        int newShortStrike = targetStrike.get().strike();

        // Select hedge strike to maintain positive net credit
        int newLongStrike = selectHedgeStrike(chain, side, newShortStrike, currentNetCredit, lotSize, stepSize);

        BigDecimal shortPremium = targetStrike.get().ltp();
        StrikeQuote hedgeQuote = chain.getQuote(newLongStrike, targetOptionType);
        BigDecimal hedgePremium = hedgeQuote != null ? hedgeQuote.ltp() : BigDecimal.ZERO;

        BigDecimal creditDelta = shortPremium.subtract(hedgePremium);

        log.info("[IronFly] {} adjustment: new short {} @{} (delta {:.2f}), new long {} @{}, credit delta ₹{}",
            side, targetOptionType, newShortStrike, targetStrike.get().delta(),
            targetOptionType, newLongStrike, creditDelta);

        return new AdjustmentStrikeSelection(newShortStrike, newLongStrike, shortPremium, hedgePremium, creditDelta);
    }

    private int deriveStrikeStep(OptionChain chain, double spot) {
        if (chain != null && chain.calls() != null && chain.calls().size() >= 2) {
            List<Integer> sortedStrikes = chain.calls().keySet().stream().sorted().toList();
            int minDiff = Integer.MAX_VALUE;
            for (int i = 1; i < sortedStrikes.size(); i++) {
                int diff = sortedStrikes.get(i) - sortedStrikes.get(i - 1);
                if (diff > 0 && diff < minDiff) {
                    minDiff = diff;
                }
            }
            if (minDiff < Integer.MAX_VALUE && minDiff > 0) {
                return minDiff;
            }
        }
        if (spot >= 30000) return 100;
        if (spot >= 10000) return 50;
        if (spot >= 1000) return 10;
        return 5;
    }

    private int selectHedgeStrike(OptionChain chain, AdjustmentSide side,
                                  int shortStrike, BigDecimal currentNetCredit, int lotSize, int stepSize) {
        OptionType type = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        int step = (side == AdjustmentSide.CALL) ? stepSize : -stepSize;

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
                                                        BigDecimal currentNetCredit, int lotSize, int stepSize) {
        OptionType type = (side == AdjustmentSide.CALL) ? OptionType.CE : OptionType.PE;
        double offset = spot * 0.02;

        int targetStrike;
        if (type == OptionType.CE) {
            targetStrike = (int) (Math.round((spot + offset) / (double) stepSize) * stepSize);
        } else {
            targetStrike = (int) (Math.round((spot - offset) / (double) stepSize) * stepSize);
        }

        StrikeQuote quote = chain.getQuote(targetStrike, type);
        BigDecimal shortPremium = quote != null ? quote.ltp() : BigDecimal.ZERO;

        int hedgeStrike = targetStrike + ((type == OptionType.CE) ? stepSize : -stepSize);
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
