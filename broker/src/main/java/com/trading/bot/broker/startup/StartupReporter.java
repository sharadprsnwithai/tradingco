package com.trading.bot.broker.startup;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Handles application lifecycle startup handshake and shutdown notifications. */
@Component
public final class StartupReporter {

  private static final Logger LOG = LoggerFactory.getLogger(StartupReporter.class);

  private final Broker broker;
  private final NotificationPublisher publisher;

  public StartupReporter(Broker broker, NotificationPublisher publisher) {
    this.broker = Objects.requireNonNull(broker, "broker must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
  }

  /**
   * Reports startup positions state upon ApplicationReadyEvent.
   *
   * @param event application ready event
   */
  @EventListener(ApplicationReadyEvent.class)
  @Order(100)
  public void reportStartup(ApplicationReadyEvent event) {
    // Startup positions handshake suppressed per user preference (only entry/exit signals sent to
    // Telegram).
  }

  /** Emits SHUTDOWN notification on bean teardown. */
  @PreDestroy
  public void reportShutdown() {
    publisher.publish(
        Notification.of(
            Severity.INFO,
            NotificationType.SHUTDOWN,
            "Trading Bot Stopped",
            "Trading bot shutdown cleanly"));
  }
}
