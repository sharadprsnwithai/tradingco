package com.trading.bot.broker.startup;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.model.Position;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import jakarta.annotation.PreDestroy;
import java.util.List;
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
    try {
      final List<Position> positions = broker.positions();
      final String body;
      if (positions.isEmpty()) {
        body = "no open positions";
      } else {
        final StringBuilder sb = new StringBuilder();
        for (final Position p : positions) {
          final String side = p.quantity() >= 0 ? "BUY" : "SELL";
          final String pnlFormatted =
              String.format(java.util.Locale.ROOT, "%.2f", p.pnl().doubleValue());
          sb.append(p.tradingsymbol())
              .append(' ')
              .append(side)
              .append(' ')
              .append(p.quantity())
              .append('@')
              .append(p.averagePrice())
              .append(" pnl=")
              .append(pnlFormatted)
              .append('\n');
        }
        body = sb.toString().trim();
      }

      publisher.publish(
          Notification.of(Severity.INFO, NotificationType.STARTUP, "Trading Bot Started", body));
    } catch (com.trading.bot.broker.api.error.BrokerException e) {
      if (LOG.isErrorEnabled()) {
        LOG.error("Failed to retrieve positions on startup: {}", e.getMessage(), e);
      }
      publisher.publish(
          Notification.of(
              Severity.INFO,
              NotificationType.STARTUP,
              "Trading Bot Started",
              "positions unavailable: " + e.getMessage()));
    }
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
