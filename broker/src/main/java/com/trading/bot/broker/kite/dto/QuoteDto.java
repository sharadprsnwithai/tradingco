package com.trading.bot.broker.kite.dto;

import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Market quote response mapping instrument keys to QuoteDataDto.
 *
 * @param status status indicator
 * @param data map of instrument key (e.g. NSE:RELIANCE) to quote data
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record QuoteDto(String status, Map<String, QuoteDataDto> data) {}
