package com.trading.bot.broker.kite.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Standard Kite error response envelope.
 *
 * @param status error status indicator
 * @param errorType categorized error type (e.g. TokenException)
 * @param message human-readable error description
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorDto(String status, String errorType, String message) {}
