package com.tradingbot.risk;

/**
 * Immutable result of a pre-trade risk check performed by the {@link RiskManager}.
 *
 * @param approved whether the signal passed all risk guardrails
 * @param ruleName the name of the risk rule that was evaluated (e.g. "PASS", "MAX_STRATEGY_LOSS_LIMIT")
 * @param reason   human-readable explanation of the check outcome
 */
public record RiskCheckResult(
    boolean approved,
    String ruleName,
    String reason
) {
    /**
     * Creates a risk check result indicating the signal passed all guardrails.
     *
     * @return an approved {@link RiskCheckResult} with default rule name and reason
     */
    public static RiskCheckResult pass() {
        return new RiskCheckResult(true, "PASS", "Risk checks approved");
    }

    /**
     * Creates a risk check result indicating the signal was rejected by a specific rule.
     *
     * @param ruleName the identifier of the rule that rejected the signal
     * @param reason   human-readable explanation of why the signal was rejected
     * @return a rejected {@link RiskCheckResult}
     */
    public static RiskCheckResult reject(String ruleName, String reason) {
        return new RiskCheckResult(false, ruleName, reason);
    }
}
