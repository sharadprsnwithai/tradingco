package com.trading.bot.broker.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class KiteSessionTest {

  private static final String API_KEY = "test-api-key";
  private static final String USER_ID = "AB1234";
  private static final String PASSWORD = "test-password";
  private static final String TOTP_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
  private static final String TOKEN_URL = "https://api.kite.trade/session/token";
  private static final String KOLKATA_ZONE = "Asia/Kolkata";

  private ListAppender<ILoggingEvent> listAppender;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(listAppender);
  }

  @Test
  void happyPathPerformsLoginServesAccessToken() {
    final KiteProperties props =
        new KiteProperties(API_KEY, USER_ID, PASSWORD, TOTP_SECRET, null, null, null, null);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(59L), ZoneId.of(KOLKATA_ZONE));
    final KiteTotp totp = new KiteTotp();

    final String expectedTotp = totp.code(TOTP_SECRET, 59L);

    server
        .expect(requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("api_key=" + API_KEY),
                        org.hamcrest.Matchers.containsString("user_id=" + USER_ID),
                        org.hamcrest.Matchers.containsString("password=" + PASSWORD),
                        org.hamcrest.Matchers.containsString("totp=" + expectedTotp))))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"access_token\":\"received-token-12345\"}}",
                MediaType.APPLICATION_JSON));

    final KiteSession session = new KiteSession(props, totp, publisher, clock, builder);

    session.login();

    server.verify();
    assertThat(session.accessToken()).isEqualTo("received-token-12345");
    assertThat(session.isDegraded()).isFalse();
  }

  @Test
  void specialCharactersInPasswordArePercentEncodedOnWire() {
    final String specialPassword = "p&ss=w0rd";
    final KiteProperties props =
        new KiteProperties(API_KEY, USER_ID, specialPassword, TOTP_SECRET, null, null, null, null);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final NotificationPublisher publisher = n -> {};
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(59L), ZoneId.of(KOLKATA_ZONE));
    final KiteTotp totp = new KiteTotp();

    server
        .expect(requestTo(TOKEN_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("password=p%26ss%3Dw0rd")))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"access_token\":\"tok\"}}",
                MediaType.APPLICATION_JSON));

    final KiteSession session = new KiteSession(props, totp, publisher, clock, builder);
    session.login();

    server.verify();
    assertThat(session.accessToken()).isEqualTo("tok");
  }

  @Test
  void errorJsonThrowsAuthExceptionLeavesTokenNull() {
    final KiteProperties props =
        new KiteProperties(API_KEY, USER_ID, PASSWORD, TOTP_SECRET, null, null, null, null);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final NotificationPublisher publisher = n -> {};
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(59L), ZoneId.of(KOLKATA_ZONE));
    final KiteTotp totp = new KiteTotp();

    server
        .expect(requestTo(TOKEN_URL))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    "{\"status\":\"error\",\"error_type\":\"TotpException\",\"message\":\"Incorrect"
                        + " TOTP\"}"));

    final KiteSession session = new KiteSession(props, totp, publisher, clock, builder);

    assertThatThrownBy(session::login)
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Incorrect TOTP");

    server.verify();
    assertThatThrownBy(session::accessToken)
        .isInstanceOf(AuthException.class)
        .hasMessageContaining("Not authenticated");
  }

  @Test
  void clockDriftOverThirtySecondsPublishesSystemAlert() {
    final KiteProperties props =
        new KiteProperties(API_KEY, USER_ID, PASSWORD, TOTP_SECRET, null, null, null, null);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final List<Notification> published = new ArrayList<>();
    final NotificationPublisher publisher = published::add;
    final Instant clientTime = Instant.parse("2026-10-23T10:00:00Z");
    final Clock clock = Clock.fixed(clientTime, ZoneId.of("UTC"));
    final KiteTotp totp = new KiteTotp();

    // Server is 120s ahead
    final Instant serverTime = clientTime.plusSeconds(120);
    final HttpHeaders headers = new HttpHeaders();
    headers.set(
        HttpHeaders.DATE,
        DateTimeFormatter.RFC_1123_DATE_TIME.format(serverTime.atZone(ZoneId.of("GMT"))));

    server
        .expect(requestTo(TOKEN_URL))
        .andRespond(
            withSuccess(
                    "{\"status\":\"success\",\"data\":{\"access_token\":\"tok\"}}",
                    MediaType.APPLICATION_JSON)
                .headers(headers));

    final KiteSession session = new KiteSession(props, totp, publisher, clock, builder);
    session.login();

    server.verify();
    assertThat(published)
        .anyMatch(
            n -> n.type() == NotificationType.SYSTEM_ALERT && n.title().contains("Clock drift"));
  }

  @Test
  void secretsCanaryLogsContainNoCredentialsOrTokens() {
    final KiteProperties props =
        new KiteProperties(API_KEY, USER_ID, PASSWORD, TOTP_SECRET, null, null, null, null);
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final NotificationPublisher publisher = n -> {};
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(59L), ZoneId.of(KOLKATA_ZONE));
    final KiteTotp totp = new KiteTotp();

    server
        .expect(requestTo(TOKEN_URL))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"access_token\":\"super-secret-access-token-999\"}}",
                MediaType.APPLICATION_JSON));

    final KiteSession session = new KiteSession(props, totp, publisher, clock, builder);
    session.login();

    server.verify();

    for (final ILoggingEvent event : listAppender.list) {
      final String msg = event.getFormattedMessage();
      assertThat(msg)
          .doesNotContain(PASSWORD)
          .doesNotContain(TOTP_SECRET)
          .doesNotContain("super-secret-access-token-999");
    }
  }
}
