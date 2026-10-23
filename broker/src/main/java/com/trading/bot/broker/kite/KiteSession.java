package com.trading.bot.broker.kite;

import com.trading.bot.broker.api.error.AuthException;
import com.trading.bot.broker.config.KiteProperties;
import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
  private static final String REQUEST_TOKEN_PREFIX = "request_token=";
  private static final String STATUS_ERROR = "error";

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
   * Performs unattended login via OAuth or direct POST /session/token with TOTP generation.
   *
   * @throws AuthException on credential failure or unexpected response
   */
  public synchronized void login() {
    if (props.apiSecret() != null && !props.apiSecret().isBlank()) {
      loginWithOAuth();
    } else {
      loginDirect();
    }
  }

  private void loginDirect() {
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

  @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
  private void loginWithOAuth() {
    try {
      final long epochSeconds = clock.instant().getEpochSecond();
      final String code = totp.code(props.totpSecret(), epochSeconds);

      final CookieManager cookieManager = new CookieManager();
      final HttpClient client =
          HttpClient.newBuilder()
              .cookieHandler(cookieManager)
              .followRedirects(HttpClient.Redirect.NEVER)
              .connectTimeout(props.http().connectTimeout())
              .build();

      // 1. POST https://kite.zerodha.com/api/login
      final String loginForm =
          "user_id="
              + URLEncoder.encode(props.userId(), StandardCharsets.UTF_8)
              + "&password="
              + URLEncoder.encode(props.password(), StandardCharsets.UTF_8);

      final HttpRequest loginReq =
          HttpRequest.newBuilder()
              .uri(URI.create("https://kite.zerodha.com/api/login"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("X-Kite-Version", "3")
              .header("Origin", "https://kite.zerodha.com")
              .header("Referer", "https://kite.zerodha.com/")
              .header(
                  "User-Agent",
                  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                      + " Gecko) Chrome/120.0.0.0 Safari/537.36")
              .POST(HttpRequest.BodyPublishers.ofString(loginForm))
              .build();

      final HttpResponse<String> loginResp =
          client.send(loginReq, HttpResponse.BodyHandlers.ofString());
      final JsonNode loginJson = objectMapper.readTree(loginResp.body());

      final String requestId = loginJson.path("data").path("request_id").asText();
      if (requestId == null || requestId.isBlank()) {
        final String errMsg = loginJson.path("message").asText(loginResp.body());
        throw new AuthException("Zerodha login rejected: " + errMsg);
      }

      // 2. POST https://kite.zerodha.com/api/twofa
      final String twofaForm =
          "user_id="
              + URLEncoder.encode(props.userId(), StandardCharsets.UTF_8)
              + "&request_id="
              + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
              + "&twofa_value="
              + URLEncoder.encode(code, StandardCharsets.UTF_8)
              + "&twofa_type=totp&skip_session=true";

      final HttpRequest twofaReq =
          HttpRequest.newBuilder()
              .uri(URI.create("https://kite.zerodha.com/api/twofa"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("X-Kite-Version", "3")
              .header("Origin", "https://kite.zerodha.com")
              .header("Referer", "https://kite.zerodha.com/")
              .header(
                  "User-Agent",
                  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                      + " Gecko) Chrome/120.0.0.0 Safari/537.36")
              .POST(HttpRequest.BodyPublishers.ofString(twofaForm))
              .build();

      final HttpResponse<String> twofaResp =
          client.send(twofaReq, HttpResponse.BodyHandlers.ofString());
      final JsonNode twofaJson = objectMapper.readTree(twofaResp.body());
      if (STATUS_ERROR.equalsIgnoreCase(twofaJson.path("status").asText())) {
        throw new AuthException("Zerodha 2FA rejected: " + twofaJson.path("message").asText());
      }

      // 3. Connect login to capture request_token
      String connectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key=" + props.apiKey();
      if (props.redirectUri() != null && !props.redirectUri().isBlank()) {
        connectUrl +=
            "&redirect_uri=" + URLEncoder.encode(props.redirectUri(), StandardCharsets.UTF_8);
      }

      String loc = connectUrl;
      String requestToken = null;
      for (int hop = 0; hop < 5; hop++) {
        final HttpRequest hopReq =
            HttpRequest.newBuilder()
                .uri(URI.create(loc))
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://kite.zerodha.com/")
                .GET()
                .build();

        final HttpResponse<String> hopResp =
            client.send(hopReq, HttpResponse.BodyHandlers.ofString());
        final String newLoc = hopResp.headers().firstValue("Location").orElse(null);
        if (newLoc != null && newLoc.contains(REQUEST_TOKEN_PREFIX)) {
          requestToken = extractRequestToken(newLoc);
          break;
        }
        if (newLoc == null) {
          break;
        }
        loc = newLoc.startsWith("http") ? newLoc : "https://kite.zerodha.com" + newLoc;
      }

      if (requestToken == null || requestToken.isBlank()) {
        throw new AuthException(
            "Headless login completed 2FA, but failed to capture request_token");
      }

      // 4. Exchange request_token on api.kite.trade/session/token
      final String checksum = sha256(props.apiKey() + requestToken + props.apiSecret());
      final MultiValueMap<String, String> tokenForm = new LinkedMultiValueMap<>();
      tokenForm.add("api_key", props.apiKey());
      tokenForm.add("request_token", requestToken);
      tokenForm.add("checksum", checksum);

      final ResponseEntity<String> response =
          restClient
              .post()
              .uri("/session/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(tokenForm)
              .retrieve()
              .toEntity(String.class);

      checkClockDrift(response.getHeaders().getFirstDate(HttpHeaders.DATE));
      parseSuccessToken(response.getBody());
    } catch (AuthException e) {
      clearAccessToken();
      throw e;
    } catch (Exception e) {
      clearAccessToken();
      throw new AuthException("Headless Kite login failed: " + e.getMessage(), e);
    }
  }

  private static String extractRequestToken(String locationUrl) {
    if (locationUrl == null || !locationUrl.contains(REQUEST_TOKEN_PREFIX)) {
      return null;
    }
    try {
      final URI locUri = URI.create(locationUrl);
      final String query = locUri.getQuery();
      if (query != null) {
        for (final String param : query.split("&")) {
          if (param.startsWith(REQUEST_TOKEN_PREFIX)) {
            return param.substring(REQUEST_TOKEN_PREFIX.length());
          }
        }
      }
    } catch (IllegalArgumentException ignored) {
      // return null
    }
    return null;
  }

  private static String sha256(String input) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder();
      for (final byte b : hash) {
        hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
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
