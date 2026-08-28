package com.tradingbot.strategy.ironfly;

import reactor.core.publisher.Mono;

/**
 * Interface for fetching option chain data from a broker.
 * Implementations provide strike-level quotes including Greeks.
 */
public interface OptionChainProvider {

    /**
     * Fetches the full option chain for a given underlying and expiry.
     *
     * @param underlying the underlying symbol (e.g., "RELIANCE")
     * @param expiry     the expiry date in "YYYY-MM-DD" format
     * @return the composite OptionChain container for all available strikes
     */
    Mono<OptionChain> getOptionChain(String underlying, String expiry);

    /**
     * Fetches the current spot price for an underlying.
     *
     * @param underlying the underlying symbol (e.g., "RELIANCE")
     * @return the current spot/LTP price
     */
    Mono<Double> getSpotPrice(String underlying);
}
