package com.tradingbot.model;

import java.math.BigDecimal;

/**
 * Represents the margin (funds) available and used in a trading account.
 *
 * @param accountId       the unique account identifier
 * @param brokerId        the broker identifier (e.g. "kite", "shoonya")
 * @param availableMargin the margin available for new positions
 * @param usedMargin      the margin currently locked in open positions
 * @param totalMargin     the total margin (available + used)
 * @param cashBalance     the raw cash balance in the account
 */
public record MarginInfo(
    String accountId,
    String brokerId,
    BigDecimal availableMargin,
    BigDecimal usedMargin,
    BigDecimal totalMargin,
    BigDecimal cashBalance
) {
    /**
     * Factory method to create a {@link MarginInfo} instance.
     *
     * @param accountId the unique account identifier
     * @param brokerId  the broker identifier
     * @param available the margin available for new positions
     * @param used      the margin currently locked in open positions
     * @param total     the total margin (available + used)
     * @param cash      the raw cash balance in the account
     * @return a new MarginInfo instance
     */
    public static MarginInfo of(String accountId, String brokerId, BigDecimal available, BigDecimal used, BigDecimal total, BigDecimal cash) {
        return new MarginInfo(accountId, brokerId, available, used, total, cash);
    }
}
