package com.trading.bot.broker.api.error;

/** Thrown when Kite rate limits are exceeded (HTTP 429). */
public final class RateLimitException extends BrokerException {

  private static final long serialVersionUID = 1L;

  public RateLimitException(String message) {
    super(message);
  }

  public RateLimitException(String message, Throwable cause) {
    super(message, cause);
  }
}
