package com.trading.bot.broker.kite.dto;

import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Account margins query response envelope.
 *
 * @param status status indicator
 * @param data raw margin segment data map
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MarginsDto(String status, Map<String, Object> data) {}
