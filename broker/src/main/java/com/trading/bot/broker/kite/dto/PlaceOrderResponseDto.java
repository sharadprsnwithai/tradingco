package com.trading.bot.broker.kite.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Successful response from POST /orders/{variety}.
 *
 * @param status status indicator
 * @param data payload containing order ID
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlaceOrderResponseDto(String status, PlaceOrderDataDto data) {

  /**
   * Order placement data payload.
   *
   * @param orderId assigned order ID
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PlaceOrderDataDto(String orderId) {}
}
