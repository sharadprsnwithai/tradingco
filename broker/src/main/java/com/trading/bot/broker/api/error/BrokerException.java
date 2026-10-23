package com.trading.bot.broker.api.error;

/** Root exception hierarchy for all broker operations. Sealed to ensure exhaustive handling. */
public abstract sealed class BrokerException extends RuntimeException
    permits AuthException,
        RateLimitException,
        NetworkException,
        AmbiguousOrderStateException,
        ApiException {

  private static final long serialVersionUID = 1L;

  protected BrokerException(String message) {
    super(message);
  }

  protected BrokerException(String message, Throwable cause) {
    super(message, cause);
  }
}
