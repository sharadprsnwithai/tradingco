package com.tradingbot.model;

import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;

import java.math.BigDecimal;

/**
 * Represents a request to place a new order.
 */
public record OrderRequest(
    String accountId,
    String brokerId,
    String symbol,
    String exchange,
    String instrumentToken,
    TransactionType transactionType,
    int quantity,
    BigDecimal price,
    BigDecimal triggerPrice,
    OrderType orderType,
    ProductType productType,
    String tag,
    String strategyId
) {
    /**
     * Creates a new {@link Builder} for constructing {@link OrderRequest} instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link OrderRequest} instances.
     */
    public static class Builder {
        private String accountId;
        private String brokerId;
        private String symbol;
        private String exchange = "NFO";
        private String instrumentToken;
        private TransactionType transactionType;
        private int quantity;
        private BigDecimal price;
        private BigDecimal triggerPrice;
        private OrderType orderType = OrderType.LIMIT;
        private ProductType productType = ProductType.MIS;
        private String tag;
        private String strategyId;

        /** Sets the account ID that will place the order. */
        public Builder accountId(String accountId) { this.accountId = accountId; return this; }
        /** Sets the broker identifier. */
        public Builder brokerId(String brokerId) { this.brokerId = brokerId; return this; }
        /** Sets the trading symbol. */
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        /** Sets the exchange (defaults to "NFO"). */
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        /** Sets the broker-specific instrument token. */
        public Builder instrumentToken(String instrumentToken) { this.instrumentToken = instrumentToken; return this; }
        /** Sets the transaction type (BUY or SELL). */
        public Builder transactionType(TransactionType transactionType) { this.transactionType = transactionType; return this; }
        /** Sets the order quantity. */
        public Builder quantity(int quantity) { this.quantity = quantity; return this; }
        /** Sets the limit price for the order. */
        public Builder price(BigDecimal price) { this.price = price; return this; }
        /** Sets the trigger price for stop-loss orders. */
        public Builder triggerPrice(BigDecimal triggerPrice) { this.triggerPrice = triggerPrice; return this; }
        /** Sets the order type (e.g. LIMIT, MARKET, SL, SL-M). */
        public Builder orderType(OrderType orderType) { this.orderType = orderType; return this; }
        /** Sets the product type (e.g. MIS, CNC, NRML). */
        public Builder productType(ProductType productType) { this.productType = productType; return this; }
        /** Sets a user-defined tag for order identification. */
        public Builder tag(String tag) { this.tag = tag; return this; }
        /** Sets the strategy ID that generated the order. */
        public Builder strategyId(String strategyId) { this.strategyId = strategyId; return this; }

        /**
         * Builds and returns the {@link OrderRequest} instance.
         *
         * @return a new OrderRequest with the configured values
         */
        public OrderRequest build() {
            return new OrderRequest(accountId, brokerId, symbol, exchange, instrumentToken, transactionType, quantity, price, triggerPrice, orderType, productType, tag, strategyId);
        }
    }
}
