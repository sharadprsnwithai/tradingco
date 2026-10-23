package com.trading.bot.broker.kite.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Successful response from POST /session/token.
 *
 * @param status status indicator
 * @param data login payload containing access token
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LoginResponseDto(String status, LoginDataDto data) {

  /**
   * Data payload containing access token.
   *
   * @param accessToken access token
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LoginDataDto(String accessToken) {}
}
