package com.trading.bot.broker.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated configuration properties for Kite Connect API credentials and timeouts.
 *
 * @param apiKey Kite API key
 * @param userId Kite user ID
 * @param password Kite account password
 * @param totpSecret Kite TOTP seed
 * @param baseUrl Kite API base URL (default https://api.kite.trade)
 * @param http HTTP timeout tunables
 * @param auth authentication and retry backoff tunables
 * @param orders order reconciliation tunables
 */
@ConfigurationProperties("kite")
@Validated
public record KiteProperties(
    @NotBlank(message = "KITE_API_KEY (kite.api-key) must not be blank") String apiKey,
    @NotBlank(message = "KITE_USER_ID (kite.user-id) must not be blank") String userId,
    @NotBlank(message = "KITE_PASSWORD (kite.password) must not be blank") String password,
    @NotBlank(message = "KITE_TOTP_SECRET (kite.totp-secret) must not be blank") String totpSecret,
    String baseUrl,
    Http http,
    Auth auth,
    Orders orders) {

  private static final String DEFAULT_BASE_URL = "https://api.kite.trade";
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_PLACE_READ_TIMEOUT = Duration.ofSeconds(15);

  private static final Duration DEFAULT_INITIAL_RETRY = Duration.ofMinutes(1);
  private static final Duration DEFAULT_MAX_RETRY = Duration.ofMinutes(20);
  private static final int DEFAULT_MAX_ATTEMPTS_PER_HOUR = 3;

  private static final Duration DEFAULT_LOST_GRACE = Duration.ofSeconds(30);

  /** Compact constructor applying nested defaults when omitted. */
  public KiteProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = DEFAULT_BASE_URL;
    }
    if (http == null) {
      http = new Http(null, null, null);
    }
    if (auth == null) {
      auth = new Auth(null, null, 0);
    }
    if (orders == null) {
      orders = new Orders(null);
    }
  }

  /**
   * HTTP timeouts for Kite API calls.
   *
   * @param connectTimeout connection timeout (default 5s)
   * @param readTimeout read timeout for queries (default 10s)
   * @param placeReadTimeout read timeout for order mutations (default 15s)
   */
  public record Http(Duration connectTimeout, Duration readTimeout, Duration placeReadTimeout) {
    public Http {
      if (connectTimeout == null) {
        connectTimeout = DEFAULT_CONNECT_TIMEOUT;
      }
      if (readTimeout == null) {
        readTimeout = DEFAULT_READ_TIMEOUT;
      }
      if (placeReadTimeout == null) {
        placeReadTimeout = DEFAULT_PLACE_READ_TIMEOUT;
      }
    }
  }

  /**
   * Authentication retry and backoff tunables.
   *
   * @param initialRetry initial backoff duration (default 1m)
   * @param maxRetry maximum backoff duration cap (default 20m)
   * @param maxAttemptsPerHour hard attempt limit per rolling 60 min (default 3)
   */
  public record Auth(Duration initialRetry, Duration maxRetry, int maxAttemptsPerHour) {
    public Auth {
      if (initialRetry == null) {
        initialRetry = DEFAULT_INITIAL_RETRY;
      }
      if (maxRetry == null) {
        maxRetry = DEFAULT_MAX_RETRY;
      }
      if (maxAttemptsPerHour <= 0) {
        maxAttemptsPerHour = DEFAULT_MAX_ATTEMPTS_PER_HOUR;
      }
    }
  }

  /**
   * Order reconciliation grace periods.
   *
   * @param lostGrace elapsed time before missing order is declared LOST (default 30s)
   */
  public record Orders(Duration lostGrace) {
    public Orders {
      if (lostGrace == null) {
        lostGrace = DEFAULT_LOST_GRACE;
      }
    }
  }
}
