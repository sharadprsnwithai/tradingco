package com.tradingbot.model;

import java.math.BigDecimal;

/**
 * Represents a tradeable instrument with broker-specific tokens and exchange metadata.
 */
public record Instrument(
    String canonicalSymbol,
    String kiteToken,
    String shoonyaToken,
    String exchange,
    String tradingSymbol,
    String name,
    int lotSize,
    BigDecimal tickSize,
    String instrumentType,
    BigDecimal strike,
    String expiry
) {
    /**
     * Creates a new {@link Builder} for constructing {@link Instrument} instances.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for constructing {@link Instrument} instances.
     */
    public static class Builder {
        private String canonicalSymbol;
        private String kiteToken;
        private String shoonyaToken;
        private String exchange;
        private String tradingSymbol;
        private String name;
        private int lotSize = 1;
        private BigDecimal tickSize = new BigDecimal("0.05");
        private String instrumentType = "EQ";
        private BigDecimal strike;
        private String expiry;

        /** Sets the canonical symbol used internally by the trading bot. */
        public Builder canonicalSymbol(String canonicalSymbol) { this.canonicalSymbol = canonicalSymbol; return this; }
        /** Sets the Kite (Zerodha) instrument token. */
        public Builder kiteToken(String kiteToken) { this.kiteToken = kiteToken; return this; }
        /** Sets the Shoonya (Finvasia) instrument token. */
        public Builder shoonyaToken(String shoonyaToken) { this.shoonyaToken = shoonyaToken; return this; }
        /** Sets the exchange (e.g. "NSE", "NFO", "BSE"). */
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        /** Sets the broker-specific trading symbol. */
        public Builder tradingSymbol(String tradingSymbol) { this.tradingSymbol = tradingSymbol; return this; }
        /** Sets the human-readable instrument name. */
        public Builder name(String name) { this.name = name; return this; }
        /** Sets the lot size for the instrument. */
        public Builder lotSize(int lotSize) { this.lotSize = lotSize; return this; }
        /** Sets the minimum tick size (price increment). */
        public Builder tickSize(BigDecimal tickSize) { this.tickSize = tickSize; return this; }
        /** Sets the instrument type (e.g. "EQ", "CE", "PE", "FUT"). */
        public Builder instrumentType(String instrumentType) { this.instrumentType = instrumentType; return this; }
        /** Sets the strike price (applicable for options). */
        public Builder strike(BigDecimal strike) { this.strike = strike; return this; }
        /** Sets the expiry date string (applicable for derivatives). */
        public Builder expiry(String expiry) { this.expiry = expiry; return this; }

        /**
         * Builds and returns the {@link Instrument} instance.
         *
         * @return a new Instrument with the configured values
         */
        public Instrument build() {
            return new Instrument(
                canonicalSymbol, kiteToken, shoonyaToken, exchange,
                tradingSymbol, name, lotSize, tickSize, instrumentType,
                strike, expiry
            );
        }
    }
}
