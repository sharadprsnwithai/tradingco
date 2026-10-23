package com.trading.bot.broker.api.error;

/** Thrown on transport connectivity or timeout failures during read operations. */
public final class NetworkException extends BrokerException {

  private static final long serialVersionUID = 1L;

  public NetworkException(String message) {
    super(message);
  }

  public NetworkException(String message, Throwable cause) {
    super(message, cause);
  }
}
