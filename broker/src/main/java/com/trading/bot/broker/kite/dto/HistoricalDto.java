package com.trading.bot.broker.kite.dto;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Historical candles response DTO from Kite Connect API.
 *
 * @param status status indicator
 * @param data candle data container
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HistoricalDto(String status, HistoricalDataDto data) {

  /**
   * Data payload containing list of candle tuples.
   *
   * @param candles list of lists representing [timestamp, open, high, low, close, volume, ...]
   */
  public record HistoricalDataDto(List<List<Object>> candles) {}
}
