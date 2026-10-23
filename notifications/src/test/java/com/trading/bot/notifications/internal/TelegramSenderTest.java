package com.trading.bot.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class TelegramSenderTest {

  private static final String CANARY_TOKEN = "secret-canary-bot-token-123456";
  private static final String CHAT_ID = "987654321";
  private static final String BASE_URL =
      "https://api.telegram.org/bot" + CANARY_TOKEN + "/sendMessage";
  private static final String FIXED_TIMESTAMP = "2026-10-23T10:00:00Z";
  private static final String UTC_ZONE = "UTC";
  private static final String SUCCESS_JSON = "{\"ok\":true}";

  private ListAppender<ILoggingEvent> listAppender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(TelegramSender.class);
    listAppender = new ListAppender<>();
    listAppender.start();
    logger.addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(listAppender);
  }

  @Test
  void happyPathDeliversFormattedPayloadWithChatId() {
    final TelegramProperties props = new TelegramProperties(CANARY_TOKEN, CHAT_ID);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse(FIXED_TIMESTAMP), ZoneId.of(UTC_ZONE));

    server
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"chat_id\":\"" + CHAT_ID + "\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[INFO]")),
                        org.hamcrest.Matchers.containsString("ORDER_FILLED"),
                        org.hamcrest.Matchers.containsString("Order executed"),
                        org.hamcrest.Matchers.containsString("Bought 10 RELIANCE"))))
        .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));

    final TelegramSender sender =
        new TelegramSender(props, builder, registry, clock, duration -> {});

    final Notification n =
        Notification.of(
            Severity.INFO, NotificationType.ORDER_FILLED, "Order executed", "Bought 10 RELIANCE");
    sender.deliver(n);

    server.verify();
  }

  @Test
  void bodyOver4096CharsIsTruncatedWithMarker() {
    final TelegramProperties props = new TelegramProperties(CANARY_TOKEN, CHAT_ID);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse(FIXED_TIMESTAMP), ZoneId.of(UTC_ZONE));

    final String hugeBody = "X".repeat(5000);

    server
        .expect(requestTo(BASE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("[WARN]"),
                        org.hamcrest.Matchers.containsString("…[truncated]"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(hugeBody)))))
        .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));

    final TelegramSender sender =
        new TelegramSender(props, builder, registry, clock, duration -> {});

    final Notification n =
        Notification.of(Severity.WARN, NotificationType.SYSTEM_ALERT, "Long alert", hugeBody);
    sender.deliver(n);

    server.verify();
  }

  @Test
  void transportFailureThreeRetriesIncrementsCounterAndLogsErrorWithoutEscaping() {
    final TelegramProperties props = new TelegramProperties(CANARY_TOKEN, CHAT_ID);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse(FIXED_TIMESTAMP), ZoneId.of(UTC_ZONE));

    // Initial attempt + 2 retries = 3 total server errors
    server.expect(requestTo(BASE_URL)).andRespond(withServerError());
    server.expect(requestTo(BASE_URL)).andRespond(withServerError());
    server.expect(requestTo(BASE_URL)).andRespond(withServerError());

    final TelegramSender sender =
        new TelegramSender(props, builder, registry, clock, duration -> {});

    final Notification n =
        Notification.of(Severity.CRITICAL, NotificationType.AUTH_DEGRADED, "Auth down", "Expired");

    // Must never throw upward
    sender.deliver(n);

    server.verify();

    final double failures = registry.get("notifications.send.failures").counter().count();
    assertThat(failures).isEqualTo(3.0);

    final boolean hasErrorLog =
        listAppender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR);
    assertThat(hasErrorLog).isTrue();
  }

  @Test
  void twoDeliveriesPacedAtLeast2000MsApartUsingFakeClock() {
    final TelegramProperties props = new TelegramProperties(CANARY_TOKEN, CHAT_ID);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    final long[] currentMillis = new long[] {1_000_000L};
    final Clock mutableClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of(UTC_ZONE);
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.ofEpochMilli(currentMillis[0]);
          }

          @Override
          public long millis() {
            return currentMillis[0];
          }
        };

    final List<Long> deliveryTimestamps = new ArrayList<>();

    server
        .expect(requestTo(BASE_URL))
        .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(BASE_URL))
        .andRespond(withSuccess(SUCCESS_JSON, MediaType.APPLICATION_JSON));

    final TelegramSender sender =
        new TelegramSender(
            props,
            builder,
            registry,
            mutableClock,
            duration -> {
              currentMillis[0] += duration.toMillis();
            });

    final Notification n1 =
        Notification.of(Severity.INFO, NotificationType.STARTUP, "Title1", "Body1");
    sender.deliver(n1);
    deliveryTimestamps.add(mutableClock.millis());

    final Notification n2 =
        Notification.of(Severity.INFO, NotificationType.STARTUP, "Title2", "Body2");
    sender.deliver(n2);
    deliveryTimestamps.add(mutableClock.millis());

    server.verify();

    assertThat(deliveryTimestamps).hasSize(2);
    final long elapsed = deliveryTimestamps.get(1) - deliveryTimestamps.get(0);
    assertThat(elapsed)
        .as("Deliveries must be paced at least 2000ms apart")
        .isGreaterThanOrEqualTo(2000L);
  }

  @Test
  void secretsCanaryNeverLogsBotToken() {
    final TelegramProperties props = new TelegramProperties(CANARY_TOKEN, CHAT_ID);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final Clock clock = Clock.fixed(Instant.parse(FIXED_TIMESTAMP), ZoneId.of(UTC_ZONE));

    server.expect(requestTo(BASE_URL)).andRespond(withServerError());
    server.expect(requestTo(BASE_URL)).andRespond(withServerError());
    server.expect(requestTo(BASE_URL)).andRespond(withServerError());

    final TelegramSender sender =
        new TelegramSender(props, builder, registry, clock, duration -> {});

    final Notification n =
        Notification.of(Severity.CRITICAL, NotificationType.SYSTEM_ALERT, "Alert", "Body");
    sender.deliver(n);

    for (final ILoggingEvent event : listAppender.list) {
      assertThat(event.getFormattedMessage())
          .as("Logs must never contain bot token")
          .doesNotContain(CANARY_TOKEN);
    }
  }
}
