package com.trading.bot.broker.kite.dto;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Portfolio holdings query response envelope.
 *
 * @param status status indicator
 * @param data list of holding entries
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HoldingDto(String status, List<HoldingItemDto> data) {

  /**
   * Individual holding entry.
   *
   * @param exchange exchange
   * @param tradingsymbol trading symbol
   * @param quantity total quantity
   * @param t1Quantity T+1 quantity
   * @param averagePrice average purchase price
   * @param lastPrice last market price
   * @param pnl profit and loss
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record HoldingItemDto(
      String exchange,
      String tradingsymbol,
      int quantity,
      int t1Quantity,
      BigDecimal averagePrice,
      BigDecimal lastPrice,
      BigDecimal pnl) {}
}
