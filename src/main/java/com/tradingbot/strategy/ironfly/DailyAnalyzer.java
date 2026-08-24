package com.tradingbot.strategy.ironfly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Pure logic evaluator for Iron Fly positions.
 * Checks profit target, stop loss, expiry guard, and decay triggers.
 * No broker calls — receives position state and returns evaluation results.
 */
@Component
public class DailyAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DailyAnalyzer.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    private static final double MARGIN_FACTOR = 0.25;

    private final double profitTargetPct;
    private final double stopLossPct;
    private final int expiryGuardDays;
    private final double decayThresholdPct;
    private final double shortLegLossPct;
    private final double hedgeProfitPct;

    public DailyAnalyzer(
        @Value("${ironfly.profit-target-pct:${ironfly.profitTargetPct:4}}") double profitTargetPct,
        @Value("${ironfly.stop-loss-pct:${ironfly.stopLossPct:8}}") double stopLossPct,
        @Value("${ironfly.expiry-guard-days:${ironfly.expiryGuardDays:4}}") int expiryGuardDays,
        @Value("${ironfly.decay-threshold-pct:${ironfly.decayThresholdPct:70}}") double decayThresholdPct,
        @Value("${ironfly.short-leg-loss-pct:${ironfly.shortLegLossPct:70}}") double shortLegLossPct,
        @Value("${ironfly.hedge-profit-pct:${ironfly.hedgeProfitPct:50}}") double hedgeProfitPct
    ) {
        this.profitTargetPct = profitTargetPct;
        this.stopLossPct = stopLossPct;
        this.expiryGuardDays = expiryGuardDays;
        this.decayThresholdPct = decayThresholdPct;
        this.shortLegLossPct = shortLegLossPct;
        this.hedgeProfitPct = hedgeProfitPct;
    }

    /**
     * Evaluates an Iron Fly position and returns the action to take.
     *
     * @param position    the current Iron Fly position
     * @param daysToExpiry number of days remaining to expiry
     * @return the evaluation result with action and reason
     */
    public EvaluationResult evaluate(IronFlyPosition position, int daysToExpiry) {
        if (position == null || position.status() == IronFlyStatus.CLOSED) {
            return EvaluationResult.noAction("Position null or already closed");
        }

        BigDecimal totalMtm = position.getTotalMtm();
        if (totalMtm == null) totalMtm = BigDecimal.ZERO;

        // Calculate deployed margin: spot * lotSize * 25%
        double spot = position.entrySpotPrice() != null ? position.entrySpotPrice().doubleValue() : 0;
        int lotSize = position.totalLotSize() > 0 ? position.totalLotSize() : 250;
        double deployedMargin = spot * lotSize * MARGIN_FACTOR;

        if (deployedMargin <= 0) {
            return EvaluationResult.noAction("Deployed margin is zero");
        }

        double pnlPerLot = totalMtm.doubleValue();
        double profitTargetAmount = deployedMargin * profitTargetPct / 100.0;
        double stopLossAmount = deployedMargin * stopLossPct / 100.0;

        // 1. Profit Target: MTM >= 4% of deployed margin
        if (pnlPerLot >= profitTargetAmount) {
            String reason = String.format("Profit target hit: P&L ₹%.2f >= %.0f%% of margin ₹%.2f",
                pnlPerLot, profitTargetPct, deployedMargin);
            log.info("[IronFly] {} - {}", position.underlying(), reason);
            return EvaluationResult.exit(Action.FULL_EXIT_TARGET, reason);
        }

        // 2. Stop Loss: MTM <= -8% of deployed margin
        if (pnlPerLot <= -stopLossAmount) {
            String reason = String.format("Stop loss hit: P&L ₹%.2f <= -%.0f%% of margin ₹%.2f",
                pnlPerLot, stopLossPct, deployedMargin);
            log.warn("[IronFly] {} - {}", position.underlying(), reason);
            return EvaluationResult.exit(Action.FULL_EXIT_SL, reason);
        }

        // 3. Expiry Guard: Days to Expiry <= 4
        if (daysToExpiry <= expiryGuardDays) {
            String reason = String.format("Expiry guard: %d days to expiry (threshold: %d)", daysToExpiry, expiryGuardDays);
            log.warn("[IronFly] {} - {}", position.underlying(), reason);
            return EvaluationResult.exit(Action.FULL_EXIT_EXPIRY, reason);
        }

        // 4. Long Call hedge profitable — sell and roll to 0.4 delta
        if (position.longCallHedge() != null) {
            double callProfit = position.longCallHedge().getProfitPercentage();
            if (callProfit >= hedgeProfitPct) {
                String reason = String.format("Long call hedge profit: %.1f%% gain (strike %d, entry ₹%.2f → now ₹%.2f)",
                    callProfit, position.longCallHedge().strike(),
                    position.longCallHedge().entryPrice(), position.longCallHedge().currentPrice());
                log.info("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_LONG_CALL_HEDGE, reason);
            }
        }

        // 5. Long Put hedge profitable — sell and roll to 0.4 delta
        if (position.longPutHedge() != null) {
            double putProfit = position.longPutHedge().getProfitPercentage();
            if (putProfit >= hedgeProfitPct) {
                String reason = String.format("Long put hedge profit: %.1f%% gain (strike %d, entry ₹%.2f → now ₹%.2f)",
                    putProfit, position.longPutHedge().strike(),
                    position.longPutHedge().entryPrice(), position.longPutHedge().currentPrice());
                log.info("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_LONG_PUT_HEDGE, reason);
            }
        }

        // 6. Short Call lost 70% — buy back and roll to 0.4 delta
        if (position.shortCall() != null) {
            double callLoss = position.shortCall().getLossPercentage();
            if (callLoss >= shortLegLossPct) {
                String reason = String.format("Short call loss: %.1f%% premium increase (strike %d, entry ₹%.2f → now ₹%.2f)",
                    callLoss, position.shortCall().strike(),
                    position.shortCall().entryPrice(), position.shortCall().currentPrice());
                log.warn("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_SHORT_CALL_LOSS, reason);
            }
        }

        // 7. Short Put lost 70% — buy back and roll to 0.4 delta
        if (position.shortPut() != null) {
            double putLoss = position.shortPut().getLossPercentage();
            if (putLoss >= shortLegLossPct) {
                String reason = String.format("Short put loss: %.1f%% premium increase (strike %d, entry ₹%.2f → now ₹%.2f)",
                    putLoss, position.shortPut().strike(),
                    position.shortPut().entryPrice(), position.shortPut().currentPrice());
                log.warn("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_SHORT_PUT_LOSS, reason);
            }
        }

        // 8. 70% Decay on Short Call
        if (position.shortCall() != null) {
            double callDecay = position.shortCall().getDecayPercentage();
            if (callDecay >= decayThresholdPct) {
                String reason = String.format("Call side decay: %.1f%% >= %.0f%% threshold (strike %d)",
                    callDecay, decayThresholdPct, position.shortCall().strike());
                log.info("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_CALL_SIDE, reason);
            }
        }

        // 9. 70% Decay on Short Put
        if (position.shortPut() != null) {
            double putDecay = position.shortPut().getDecayPercentage();
            if (putDecay >= decayThresholdPct) {
                String reason = String.format("Put side decay: %.1f%% >= %.0f%% threshold (strike %d)",
                    putDecay, decayThresholdPct, position.shortPut().strike());
                log.info("[IronFly] {} - {}", position.underlying(), reason);
                return EvaluationResult.adjust(Action.ADJUST_PUT_SIDE, reason);
            }
        }

        double pnlPct = pnlPerLot / deployedMargin * 100;
        return EvaluationResult.noAction(String.format("No trigger - P&L: ₹%.2f (%.1f%% of margin)", pnlPerLot, pnlPct));
    }

    /**
     * Calculates days to expiry from the underlying's expiry date.
     */
    public int getDaysToExpiry(LocalDate expiryDate) {
        LocalDate today = LocalDate.now(IST_ZONE);
        return (int) ChronoUnit.DAYS.between(today, expiryDate);
    }

    public enum Action {
        NO_ACTION,
        FULL_EXIT_TARGET,
        FULL_EXIT_SL,
        FULL_EXIT_EXPIRY,
        ADJUST_CALL_SIDE,
        ADJUST_PUT_SIDE,
        ADJUST_SHORT_CALL_LOSS,
        ADJUST_SHORT_PUT_LOSS,
        ADJUST_LONG_CALL_HEDGE,
        ADJUST_LONG_PUT_HEDGE
    }

    public record EvaluationResult(Action action, String reason, boolean isExit, boolean isAdjust) {
        static EvaluationResult exit(Action action, String reason) {
            return new EvaluationResult(action, reason, true, false);
        }

        static EvaluationResult adjust(Action action, String reason) {
            return new EvaluationResult(action, reason, false, true);
        }

        static EvaluationResult noAction(String reason) {
            return new EvaluationResult(Action.NO_ACTION, reason, false, false);
        }
    }
}
