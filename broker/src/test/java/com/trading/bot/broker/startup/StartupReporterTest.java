package com.trading.bot.broker.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.api.model.Exchange;
import com.trading.bot.broker.api.model.Position;
import com.trading.bot.broker.api.model.Product;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class StartupReporterTest {

  private Position samplePosition(String symbol, int qty, BigDecimal avg, BigDecimal pnl) {
    return new Position(Exchange.NSE, symbol, Product.MIS, qty, 0, 1, avg, pnl);
  }

  @Test
  void twoPositionsEmitExactlyOneStartupEventWithSymbolsAndPnl() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    when(broker.positions())
        .thenReturn(
            List.of(
                samplePosition(
                    "RELIANCE", 10, BigDecimal.valueOf(2500), BigDecimal.valueOf(150.50)),
                samplePosition("TCS", -5, BigDecimal.valueOf(3500), BigDecimal.valueOf(-75.25))));

    final StartupReporter reporter = new StartupReporter(broker, publisher);
    final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    reporter.reportStartup(event);

    assertThat(published).hasSize(1);
    final Notification startup = published.get(0);
    assertThat(startup.type()).isEqualTo(NotificationType.STARTUP);
    assertThat(startup.body())
        .contains("RELIANCE")
        .contains("150.50")
        .contains("TCS")
        .contains("-75.25");
  }

  @Test
  void emptyPositionsListEmitsNoOpenPositionsText() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    when(broker.positions()).thenReturn(List.of());

    final StartupReporter reporter = new StartupReporter(broker, publisher);
    final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    reporter.reportStartup(event);

    assertThat(published).hasSize(1);
    final Notification startup = published.get(0);
    assertThat(startup.type()).isEqualTo(NotificationType.STARTUP);
    assertThat(startup.body()).contains("no open positions");
  }

  @Test
  void positionsExceptionEmitsPositionsUnavailableWithoutRethrowing() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    when(broker.positions()).thenThrow(new AuthException("Kite session expired"));

    final StartupReporter reporter = new StartupReporter(broker, publisher);
    final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    // Must not throw upward
    reporter.reportStartup(event);

    assertThat(published).hasSize(1);
    final Notification startup = published.get(0);
    assertThat(startup.type()).isEqualTo(NotificationType.STARTUP);
    assertThat(startup.body()).contains("positions unavailable").contains("Kite session expired");
  }

  @Test
  void singleApplicationReadyEventPublishesExactlyOnce() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    when(broker.positions()).thenReturn(List.of());

    final StartupReporter reporter = new StartupReporter(broker, publisher);
    final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    reporter.reportStartup(event);

    assertThat(published).hasSize(1);
  }
}
