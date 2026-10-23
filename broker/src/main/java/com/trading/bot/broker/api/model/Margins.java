package com.trading.bot.broker.api.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Account funds and margin availability.
 *
 * @param funds aggregate funds summary
 * @param segments segment-specific margin breakdown (equity, commodity, etc.)
 */
public record Margins(
    Map<String, BigDecimal> funds, Map<String, Map<String, BigDecimal>> segments) {

  public Margins {
    funds = funds == null ? Map.of() : Map.copyOf(funds);
    segments = segments == null ? Map.of() : Map.copyOf(segments);
  }
}
