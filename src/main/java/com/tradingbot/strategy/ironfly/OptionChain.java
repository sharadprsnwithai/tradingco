package com.tradingbot.strategy.ironfly;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Composite container for option chain data separated by Call and Put options.
 * Prevents key collision when Call and Put options share the same strike price.
 */
public record OptionChain(
    String underlying,
    String expiry,
    Map<Integer, StrikeQuote> calls,
    Map<Integer, StrikeQuote> puts
) {
    public OptionChain {
        calls = calls != null ? Collections.unmodifiableMap(calls) : Collections.emptyMap();
        puts = puts != null ? Collections.unmodifiableMap(puts) : Collections.emptyMap();
    }

    public StrikeQuote getCall(int strike) {
        return calls.get(strike);
    }

    public StrikeQuote getPut(int strike) {
        return puts.get(strike);
    }

    public StrikeQuote getQuote(int strike, OptionType type) {
        return type == OptionType.CE ? getCall(strike) : getPut(strike);
    }

    public Set<Integer> getAllStrikes() {
        Set<Integer> strikes = new TreeSet<>(calls.keySet());
        strikes.addAll(puts.keySet());
        return strikes;
    }

    public boolean isEmpty() {
        return calls.isEmpty() && puts.isEmpty();
    }

    public static OptionChain empty(String underlying, String expiry) {
        return new OptionChain(underlying, expiry, Collections.emptyMap(), Collections.emptyMap());
    }
}
