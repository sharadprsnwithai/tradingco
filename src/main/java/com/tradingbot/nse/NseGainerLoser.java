package com.tradingbot.nse;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    @JsonProperty("change") @JsonAlias({"net_price", "change"}) double change,
    @JsonProperty("pChange") @JsonAlias({"perChange", "pChange"}) double pChange,
    @JsonProperty("open") @JsonAlias({"open_price", "open"}) double open,
    @JsonProperty("high") @JsonAlias({"high_price", "high"}) double high,
    @JsonProperty("low") @JsonAlias({"low_price", "low"}) double low,
    @JsonProperty("previousClose") @JsonAlias({"prev_price", "previousClose", "close"}) double previousClose,
    @JsonProperty("totalTradedVolume") @JsonAlias({"trade_quantity", "totalTradedVolume", "volume"}) long totalTradedVolume
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
