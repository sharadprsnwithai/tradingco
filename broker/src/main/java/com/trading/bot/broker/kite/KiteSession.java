package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Handles unattended daily login via Kite Connect token endpoint and measures clock drift. */
@Component
public final class KiteSession {

  private static final Logger LOG = LoggerFactory.getLogger(KiteSession.class);
  private static final Duration DRIFT_THRESHOLD = Duration.ofSeconds(30);

  private final KiteProperties props;
  private final KiteTotp totp;
  private final NotificationPublisher publisher;
  private final Clock clock;
  private final RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private volatile String currentAccessToken;
  private volatile boolean degraded;

  public KiteSession(
      KiteProperties props,
      KiteTotp totp,
      NotificationPublisher publisher,
      Clock clock,
      RestClient.Builder restClientBuilder) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    this.totp = Objects.requireNonNull(totp, "totp must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");

    this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
  }

  @Autowired
  public KiteSession(
      KiteProperties props,
      KiteTotp totp,
      NotificationPublisher publisher,
      RestClient.Builder restClientBuilder) {
    this(props, totp, publisher, Clock.system(ZoneId.of("Asia/Kolkata")), restClientBuilder);
  }

  /**
   * Returns current active access token.
   *
   * @return access token
   * @throws AuthException if unauthenticated or session degraded
   */
  public String accessToken() {
    final String token = currentAccessToken;
    if (token == null || degraded) {
      throw new AuthException("Not authenticated with Kite");
    }
    return token;
  }

  /**
   * Returns whether session is in a degraded state.
   *
   * @return true if degraded
   */
  public boolean isDegraded() {
    return degraded;
  }

  /**
   * Updates degraded status (managed by SessionRefresher in Task 10).
   *
   * @param degraded degraded state
   */
  public void setDegraded(boolean degraded) {
    this.degraded = degraded;
  }

  @SuppressWarnings("PMD.NullAssignment")
  private void clearAccessToken() {
    this.currentAccessToken = null;
  }

  /**
   * Performs unattended login via POST /session/token with TOTP generation.
   *
   * @throws AuthException on credential failure or unexpected response
   */
  public synchronized void login() {
    final long epochSeconds = clock.instant().getEpochSecond();
    final String code = totp.code(props.totpSecret(), epochSeconds);

    final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("api_key", props.apiKey());
    form.add("user_id", props.userId());
    form.add("password", props.password());
    form.add("totp", code);

    final ResponseEntity<String> response;
    try {
      response =
          restClient
              .post()
              .uri("/session/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .toEntity(String.class);
    } catch (HttpStatusCodeException e) {
      clearAccessToken();
      final String kiteMessage = extractErrorMessage(e.getResponseBodyAsString(), e.getMessage());
      throw new AuthException(kiteMessage, e);
    } catch (RestClientException e) {
      clearAccessToken();
      throw new AuthException("Login request failed: " + e.getMessage(), e);
    }

    checkClockDrift(response.getHeaders().getFirstDate(HttpHeaders.DATE));
    parseSuccessToken(response.getBody());
  }

  private void checkClockDrift(long dateHeaderEpochMillis) {
    if (dateHeaderEpochMillis > 0) {
      final Instant serverTime = Instant.ofEpochMilli(dateHeaderEpochMillis);
      final Duration drift = Duration.between(clock.instant(), serverTime).abs();
      if (drift.compareTo(DRIFT_THRESHOLD) > 0) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("Clock drift detected: {}s skew against Kite server", drift.toSeconds());
        }
        publisher.publish(
            Notification.of(
                Severity.WARN,
                NotificationType.SYSTEM_ALERT,
                "Clock drift",
                "Clock drift measured at " + drift.toSeconds() + "s against Kite server"));
      }
    }
  }

  private void parseSuccessToken(String responseBody) {
    try {
      final JsonNode root = objectMapper.readTree(responseBody);
      final String token = root.path("data").path("access_token").asText();
      if (token == null || token.isBlank()) {
        throw new AuthException("Login response did not contain access_token");
      }
      this.currentAccessToken = token;
      this.degraded = false;
    } catch (AuthException e) {
      throw e;
    } catch (JacksonException e) {
      throw new AuthException("Failed to parse Kite login response", e);
    }
  }

  private String extractErrorMessage(String body, String fallback) {
    if (body == null || body.isBlank()) {
      return fallback;
    }
    try {
      final JsonNode root = objectMapper.readTree(body);
      final JsonNode msgNode = root.path("message");
      if (!msgNode.isMissingNode() && !msgNode.asText().isBlank()) {
        return msgNode.asText();
      }
    } catch (JacksonException ignored) {
      // return fallback
    }
    return fallback;
  }
}
