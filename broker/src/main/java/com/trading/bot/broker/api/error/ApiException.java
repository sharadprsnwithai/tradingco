package com.trading.bot.broker.api.error;

/** Thrown when Kite responds with an API error envelope. */
public final class ApiException extends BrokerException {

  private static final long serialVersionUID = 1L;

  private final String errType;
  private final int statusCode;
  private final String messageText;

  public ApiException(String errorType, int status, String kiteMessage, Throwable cause) {
    super("Kite API error [" + errorType + ", HTTP " + status + "]: " + kiteMessage, cause);
    this.errType = errorType;
    this.statusCode = status;
    this.messageText = kiteMessage;
  }

  public ApiException(String errorType, int status, String kiteMessage) {
    this(errorType, status, kiteMessage, null);
  }

  public String errorType() {
    return errType;
  }

  public int status() {
    return statusCode;
  }

  public String kiteMessage() {
    return messageText;
  }
}
