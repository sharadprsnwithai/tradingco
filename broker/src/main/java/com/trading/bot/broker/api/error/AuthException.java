package com.trading.bot.broker.api.error;

/** Thrown when broker authentication fails or daily session expires. */
public final class AuthException extends BrokerException {

  private static final long serialVersionUID = 1L;

  public AuthException(String message) {
    super(message);
  }

  public AuthException(String message, Throwable cause) {
    super(message, cause);
  }
}
