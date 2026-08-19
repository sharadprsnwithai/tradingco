package com.tradingbot.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a real-time market tick with price, volume, and order book data.
 */
public record Tick(
    String brokerId,
    String symbol,
    String exchange,
    String instrumentToken,
    BigDecimal ltp,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    BigDecimal buyPrice1,
    int buyQty1,
    BigDecimal sellPrice1,
    int sellQty1,
    Instant timestamp
) {
    /**
     * Creates a new {@link Builder} for constructing {@link Tick} instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link Tick} instances.
     */
    public static class Builder {
        private String brokerId;
        private String symbol;
        private String exchange;
        private String instrumentToken;
        private BigDecimal ltp;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;
        private BigDecimal buyPrice1;
        private int buyQty1;
        private BigDecimal sellPrice1;
        private int sellQty1;
        private Instant timestamp;

        /** Sets the broker identifier. */
        public Builder brokerId(String brokerId) { this.brokerId = brokerId; return this; }
        /** Sets the trading symbol. */
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        /** Sets the exchange (e.g. "NSE", "NFO"). */
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        /** Sets the broker-specific instrument token. */
        public Builder instrumentToken(String instrumentToken) { this.instrumentToken = instrumentToken; return this; }
        /** Sets the last traded price. */
        public Builder ltp(BigDecimal ltp) { this.ltp = ltp; return this; }
        /** Sets the day's opening price. */
        public Builder open(BigDecimal open) { this.open = open; return this; }
        /** Sets the day's highest price. */
        public Builder high(BigDecimal high) { this.high = high; return this; }
        /** Sets the day's lowest price. */
        public Builder low(BigDecimal low) { this.low = low; return this; }
        /** Sets the previous closing price. */
        public Builder close(BigDecimal close) { this.close = close; return this; }
        /** Sets the total volume traded. */
        public Builder volume(long volume) { this.volume = volume; return this; }
        /** Sets the best (top) bid price. */
        public Builder buyPrice1(BigDecimal buyPrice1) { this.buyPrice1 = buyPrice1; return this; }
        /** Sets the best (top) bid quantity. */
        public Builder buyQty1(int buyQty1) { this.buyQty1 = buyQty1; return this; }
        /** Sets the best (top) ask price. */
        public Builder sellPrice1(BigDecimal sellPrice1) { this.sellPrice1 = sellPrice1; return this; }
        /** Sets the best (top) ask quantity. */
        public Builder sellQty1(int sellQty1) { this.sellQty1 = sellQty1; return this; }
        /** Sets the tick timestamp. */
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }

        /**
         * Builds and returns the {@link Tick} instance.
         * If {@code timestamp} is not set, it defaults to the current time.
         *
         * @return a new Tick with the configured values
         */
        public Tick build() {
            return new Tick(brokerId, symbol, exchange, instrumentToken, ltp, open, high, low, close, volume, buyPrice1, buyQty1, sellPrice1, sellQty1, timestamp != null ? timestamp : Instant.now());
        }
    }
}
