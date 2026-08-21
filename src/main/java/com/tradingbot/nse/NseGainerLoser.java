package com.tradingbot.nse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single stock entry from NSE India gainers or losers API response.
 *
 * @param symbol       the trading symbol
 * @param series       the series segment (e.g., "EQ")
 * @param ltp          last traded price
 * @param change       absolute price change
 * @param pChange      percentage change
 * @param open         opening price
 * @param high         day high
 * @param low          day low
 * @param previousClose previous close price
 * @param totalTradedVolume total traded volume
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NseGainerLoser(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("series") String series,
    @JsonProperty("ltp") double ltp,
    @JsonProperty("change") double change,
    @JsonProperty("pChange") double pChange,
    @JsonProperty("open") double open,
    @JsonProperty("high") double high,
    @JsonProperty("low") double low,
    @JsonProperty("previousClose") double previousClose,
    @JsonProperty("totalTradedVolume") long totalTradedVolume
) {

    /**
     * Full NSE India gainers/losers API response structure.
     *
     * @param data the list of stock entries
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NseResponse(
        @JsonProperty("data") List<NseGainerLoser> data
    ) {}
}
