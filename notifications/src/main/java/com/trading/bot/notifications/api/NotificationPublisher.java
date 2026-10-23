package com.trading.bot.notifications.api;

/** Outbound port for emitting notifications; producers depend on this, never on implementations. */
// Single-method port is intentional: implementations are lambdas (senders, loggers, no-ops).
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface NotificationPublisher {

  void publish(Notification n);
}
