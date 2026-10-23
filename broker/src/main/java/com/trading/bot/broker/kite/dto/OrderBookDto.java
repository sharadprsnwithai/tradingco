package com.trading.bot.broker.kite.dto;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Order book query response containing a list of orders.
 *
 * @param status status indicator
 * @param data list of order DTOs
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderBookDto(String status, List<OrderDto> data) {}
