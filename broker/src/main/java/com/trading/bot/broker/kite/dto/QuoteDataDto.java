package com.trading.bot.broker.kite.dto;

import java.math.BigDecimal;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Quote market depth and OHLC data for an instrument.
 *
 * @param instrumentToken numeric instrument token
 * @param lastPrice last traded price
 * @param ohlc open, high, low, close values
 * @param volume total volume
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QuoteDataDto(
    long instrumentToken, BigDecimal lastPrice, QuoteOhlcDto ohlc, long volume) {

  /**
   * OHLC candle summary.
   *
   * @param open day open
   * @param high day high
   * @param low day low
   * @param close previous close
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record QuoteOhlcDto(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}
}
