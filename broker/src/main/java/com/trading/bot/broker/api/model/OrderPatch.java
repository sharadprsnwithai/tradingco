package com.trading.bot.broker.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Patch request to modify an open order.
 *
 * @param quantity optional updated quantity
 * @param price optional updated price
 * @param triggerPrice optional updated trigger price
 */
public record OrderPatch(
    OptionalInt quantity, Optional<BigDecimal> price, Optional<BigDecimal> triggerPrice) {

  public OrderPatch {
    Objects.requireNonNull(quantity, "quantity OptionalInt must not be null");
    Objects.requireNonNull(price, "price Optional must not be null");
    Objects.requireNonNull(triggerPrice, "triggerPrice Optional must not be null");
    if (quantity.isPresent() && quantity.getAsInt() <= 0) {
      throw new IllegalArgumentException("Modified quantity must be positive");
    }
  }
}
