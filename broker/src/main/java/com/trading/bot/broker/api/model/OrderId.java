package com.trading.bot.broker.api.model;

import java.util.Objects;

/**
 * Strongly typed order identifier assigned by Kite.
 *
 * @param value string identifier
 */
public record OrderId(String value) {
  public OrderId {
    Objects.requireNonNull(value, "OrderId value must not be null");
  }
}
