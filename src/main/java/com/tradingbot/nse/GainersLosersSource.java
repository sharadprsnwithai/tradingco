package com.tradingbot.nse;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Source of Top Gainers / Top Losers equity lists used by the Lowest Volume Reversal
 * strategy for daily stock selection at 09:26 IST.
 *
 * Implementations:
 *  - {@link NseIndiaClient} (scrapes NSE India's live-analysis API)
 *  - {@link KiteGainersLosersProvider} (derives the list from Kite quotes of the F&O universe)
 */
public interface GainersLosersSource {

    /** Returns the current Top Gainers list (highest % change vs previous close). */
    Mono<List<NseGainerLoser>> fetchGainers();

    /** Returns the current Top Losers list (lowest % change vs previous close). */
    Mono<List<NseGainerLoser>> fetchLosers();
}
