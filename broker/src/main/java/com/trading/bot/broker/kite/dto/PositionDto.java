package com.trading.bot.broker.kite.dto;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Portfolio positions query response envelope.
 *
 * @param status status indicator
 * @param data position data arrays
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PositionDto(String status, PositionDataDto data) {

  /**
   * Position arrays for net and day positions.
   *
   * @param net net positions
   * @param day day positions
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PositionDataDto(List<PositionItemDto> net, List<PositionItemDto> day) {}

  /**
   * Individual position entry.
   *
   * @param exchange exchange
   * @param tradingsymbol trading symbol
   * @param product product
   * @param quantity net quantity
   * @param overnightQuantity overnight quantity
   * @param multiplier lot size multiplier
   * @param averagePrice average entry price
   * @param pnl profit and loss
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PositionItemDto(
      String exchange,
      String tradingsymbol,
      String product,
      int quantity,
      int overnightQuantity,
      int multiplier,
      BigDecimal averagePrice,
      BigDecimal pnl) {}
}
