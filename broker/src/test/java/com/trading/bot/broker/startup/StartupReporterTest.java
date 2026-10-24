package com.trading.bot.broker.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.trading.bot.broker.api.Broker;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class StartupReporterTest {

  @Test
  void startupReporterDoesNotPublishStartupMessage() {
    final Broker broker = mock(Broker.class);
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;

    final StartupReporter reporter = new StartupReporter(broker, publisher);
    final ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);

    reporter.reportStartup(event);

    assertThat(published).isEmpty();
  }
}
