package com.tradingbot.model;

import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.ProductType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents an open or closed position with quantity, average prices, and P&L details.
 */
public record Position(
    String accountId,
    String brokerId,
    String symbol,
    String exchange,
    String instrumentToken,
    ProductType productType,
    BookType bookType,
    int netQuantity,
    int buyQuantity,
    int sellQuantity,
    BigDecimal buyAveragePrice,
    BigDecimal sellAveragePrice,
    BigDecimal ltp,
    BigDecimal mtmPnl,
    BigDecimal realizedPnl,
    BigDecimal unrealizedPnl,
    boolean autoSquareOff,
    Instant updatedAt
) {
    /**
     * Creates a new {@link Builder} for constructing {@link Position} instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link Position} instances.
     */
    public static class Builder {
        private String accountId;
        private String brokerId;
        private String symbol;
        private String exchange = "NFO";
        private String instrumentToken;
        private ProductType productType = ProductType.MIS;
        private BookType bookType = BookType.INTRADAY;
        private int netQuantity = 0;
        private int buyQuantity = 0;
        private int sellQuantity = 0;
        private BigDecimal buyAveragePrice = BigDecimal.ZERO;
        private BigDecimal sellAveragePrice = BigDecimal.ZERO;
        private BigDecimal ltp = BigDecimal.ZERO;
        private BigDecimal mtmPnl = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal unrealizedPnl = BigDecimal.ZERO;
        private boolean autoSquareOff = true;
        private Instant updatedAt;

        /** Sets the account ID holding the position. */
        public Builder accountId(String accountId) { this.accountId = accountId; return this; }
        /** Sets the broker identifier. */
        public Builder brokerId(String brokerId) { this.brokerId = brokerId; return this; }
        /** Sets the trading symbol. */
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        /** Sets the exchange (defaults to "NFO"). */
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        /** Sets the broker-specific instrument token. */
        public Builder instrumentToken(String instrumentToken) { this.instrumentToken = instrumentToken; return this; }
        /** Sets the product type (e.g. MIS, CNC, NRML). */
        public Builder productType(ProductType productType) { this.productType = productType; return this; }
        /** Sets the book type (e.g. INTRADAY, CARRYFORWARD). */
        public Builder bookType(BookType bookType) { this.bookType = bookType; return this; }
        /** Sets the net quantity (positive for long, negative for short). */
        public Builder netQuantity(int netQuantity) { this.netQuantity = netQuantity; return this; }
        /** Sets the total buy quantity. */
        public Builder buyQuantity(int buyQuantity) { this.buyQuantity = buyQuantity; return this; }
        /** Sets the total sell quantity. */
        public Builder sellQuantity(int sellQuantity) { this.sellQuantity = sellQuantity; return this; }
        /** Sets the average buy price. */
        public Builder buyAveragePrice(BigDecimal buyAveragePrice) { this.buyAveragePrice = buyAveragePrice; return this; }
        /** Sets the average sell price. */
        public Builder sellAveragePrice(BigDecimal sellAveragePrice) { this.sellAveragePrice = sellAveragePrice; return this; }
        /** Sets the last traded price. */
        public Builder ltp(BigDecimal ltp) { this.ltp = ltp; return this; }
        /** Sets the mark-to-market profit and loss. */
        public Builder mtmPnl(BigDecimal mtmPnl) { this.mtmPnl = mtmPnl; return this; }
        /** Sets the realized profit and loss. */
        public Builder realizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; return this; }
        /** Sets the unrealized (open) profit and loss. */
        public Builder unrealizedPnl(BigDecimal unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; return this; }
        /** Sets whether this position should be auto-squared off at EOD (default true). */
        public Builder autoSquareOff(boolean autoSquareOff) { this.autoSquareOff = autoSquareOff; return this; }
        /** Sets the last-updated timestamp. */
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        /**
         * Builds and returns the {@link Position} instance.
         * If {@code updatedAt} is not set, it defaults to the current time.
         *
         * @return a new Position with the configured values
         */
        public Position build() {
            return new Position(
                accountId, brokerId, symbol, exchange, instrumentToken,
                productType, bookType, netQuantity, buyQuantity, sellQuantity,
                buyAveragePrice, sellAveragePrice, ltp, mtmPnl, realizedPnl, unrealizedPnl,
                autoSquareOff,
                updatedAt != null ? updatedAt : Instant.now()
            );
        }
    }
}
