package com.tradingbot.model;

import com.tradingbot.model.enums.BookType;
import com.tradingbot.model.enums.OrderStatus;
import com.tradingbot.model.enums.OrderType;
import com.tradingbot.model.enums.ProductType;
import com.tradingbot.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a complete order with broker and execution details.
 */
public record Order(
    String id,
    String brokerOrderId,
    String accountId,
    String brokerId,
    String strategyId,
    String symbol,
    String exchange,
    String instrumentToken,
    TransactionType transactionType,
    int quantity,
    int filledQuantity,
    BigDecimal price,
    BigDecimal triggerPrice,
    BigDecimal averagePrice,
    OrderType orderType,
    ProductType productType,
    BookType bookType,
    OrderStatus status,
    String statusMessage,
    String tag,
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Creates a new {@link Builder} for constructing {@link Order} instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link Order} instances.
     */
    public static class Builder {
        private String id;
        private String brokerOrderId;
        private String accountId;
        private String brokerId;
        private String strategyId;
        private String symbol;
        private String exchange = "NFO";
        private String instrumentToken;
        private TransactionType transactionType;
        private int quantity;
        private int filledQuantity = 0;
        private BigDecimal price;
        private BigDecimal triggerPrice;
        private BigDecimal averagePrice = BigDecimal.ZERO;
        private OrderType orderType = OrderType.LIMIT;
        private ProductType productType = ProductType.MIS;
        private BookType bookType = BookType.INTRADAY;
        private OrderStatus status = OrderStatus.PENDING;
        private String statusMessage;
        private String tag;
        private Instant createdAt;
        private Instant updatedAt;

        /** Sets the internal order ID. */
        public Builder id(String id) { this.id = id; return this; }
        /** Sets the broker-assigned order ID. */
        public Builder brokerOrderId(String brokerOrderId) { this.brokerOrderId = brokerOrderId; return this; }
        /** Sets the account ID that placed the order. */
        public Builder accountId(String accountId) { this.accountId = accountId; return this; }
        /** Sets the broker identifier. */
        public Builder brokerId(String brokerId) { this.brokerId = brokerId; return this; }
        /** Sets the strategy ID that generated the order. */
        public Builder strategyId(String strategyId) { this.strategyId = strategyId; return this; }
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
        /** Sets the number of shares/lots already filled. */
        public Builder filledQuantity(int filledQuantity) { this.filledQuantity = filledQuantity; return this; }
        /** Sets the limit price for the order. */
        public Builder price(BigDecimal price) { this.price = price; return this; }
        /** Sets the trigger price for stop-loss orders. */
        public Builder triggerPrice(BigDecimal triggerPrice) { this.triggerPrice = triggerPrice; return this; }
        /** Sets the average execution price. */
        public Builder averagePrice(BigDecimal averagePrice) { this.averagePrice = averagePrice; return this; }
        /** Sets the order type (e.g. LIMIT, MARKET, SL, SL-M). */
        public Builder orderType(OrderType orderType) { this.orderType = orderType; return this; }
        /** Sets the product type (e.g. MIS, CNC, NRML). */
        public Builder productType(ProductType productType) { this.productType = productType; return this; }
        /** Sets the book type (e.g. INTRADAY, CARRYFORWARD). */
        public Builder bookType(BookType bookType) { this.bookType = bookType; return this; }
        /** Sets the current order status. */
        public Builder status(OrderStatus status) { this.status = status; return this; }
        /** Sets the status message from the broker. */
        public Builder statusMessage(String statusMessage) { this.statusMessage = statusMessage; return this; }
        /** Sets a user-defined tag for order identification. */
        public Builder tag(String tag) { this.tag = tag; return this; }
        /** Sets the order creation timestamp. */
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        /** Sets the order last-updated timestamp. */
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        /**
         * Builds and returns the {@link Order} instance.
         * If {@code createdAt} or {@code updatedAt} are not set, they default to the current time.
         *
         * @return a new Order with the configured values
         */
        public Order build() {
            Instant now = Instant.now();
            return new Order(
                id, brokerOrderId, accountId, brokerId, strategyId, symbol, exchange, instrumentToken,
                transactionType, quantity, filledQuantity, price, triggerPrice, averagePrice,
                orderType, productType, bookType, status, statusMessage, tag,
                createdAt != null ? createdAt : now, updatedAt != null ? updatedAt : now
            );
        }
    }
}
