package com.trading.bot.broker.api.error;

/**
 * Thrown when an order mutation times out or fails after bytes were sent, leaving state ambiguous.
 */
public final class AmbiguousOrderStateException extends BrokerException {

  private static final long serialVersionUID = 1L;

  private final String op;
  private final String corrId;
  private final String det;

  public AmbiguousOrderStateException(
      String operation, String correlationId, String detail, Throwable cause) {
    super(
        "Ambiguous order state for "
            + operation
            + " [correlationId="
            + correlationId
            + "]: "
            + detail,
        cause);
    this.op = operation;
    this.corrId = correlationId;
    this.det = detail;
  }

  public AmbiguousOrderStateException(String operation, String correlationId, String detail) {
    this(operation, correlationId, detail, null);
  }

  public String operation() {
    return op;
  }

  public String correlationId() {
    return corrId;
  }

  public String detail() {
    return det;
  }
}
