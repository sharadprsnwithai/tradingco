package com.trading.bot.notifications.internal;

import com.trading.bot.notifications.api.Notification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers notifications to Telegram via HTTP sendMessage with pacing, chunking, and retry
 * fallback.
 */
@Component
public final class TelegramSender implements NotificationTransport {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramSender.class);
  private static final int MAX_MESSAGE_LENGTH = 4096;
  private static final String TRUNCATION_MARKER = "…[truncated]";
  private static final long MIN_INTERVAL_MILLIS = 2143L;
  private static final int MAX_ATTEMPTS = 3;

  private final TelegramProperties props;
  private final RestClient restClient;
  private final Counter failureCounter;
  private final Clock clock;
  private final Sleeper sleeper;
  private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);
  private final Object rateLimitLock = new Object();
  private long nextAllowedTime;

  /** Functional interface for delay sleeping during pacing and retries. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration);
  }

  @Autowired
  public TelegramSender(
      TelegramProperties props,
      RestClient.Builder restClientBuilder,
      MeterRegistry registry,
      Clock clock) {
    this(
        props,
        restClientBuilder,
        registry,
        clock,
        duration -> {
          try {
            Thread.sleep(duration.toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  TelegramSender(
      TelegramProperties props,
      RestClient.Builder restClientBuilder,
      MeterRegistry registry,
      Clock clock,
      Sleeper sleeper) {
    this.props = Objects.requireNonNull(props, "props must not be null");
    Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null");
    Objects.requireNonNull(registry, "registry must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");

    this.restClient = restClientBuilder.build();
    this.failureCounter = registry.counter("notifications.send.failures");
  }

  /**
   * Returns current count of consecutive send failures (for health indicator).
   *
   * @return consecutive failures count
   */
  public int consecutiveFailures() {
    return consecutiveFailureCount.get();
  }

  @Override
  public void deliver(Notification n) {
    if (n == null) {
      return;
    }

    final String text = formatMessage(n);
    final String url = "https://api.telegram.org/bot" + props.botToken() + "/sendMessage";
    final Map<String, String> payload = Map.of("chat_id", props.chatId(), "text", text);

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      paceSend();

      try {
        restClient
            .post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .toBodilessEntity();

        consecutiveFailureCount.set(0);
        return;
      } catch (RestClientException e) {
        failureCounter.increment();
        consecutiveFailureCount.incrementAndGet();

        if (attempt < MAX_ATTEMPTS) {
          sleeper.sleep(Duration.ofMillis(200L * attempt));
        } else {
          if (LOG.isErrorEnabled()) {
            LOG.error(
                "Failed to deliver notification to Telegram after {} attempts: {}",
                MAX_ATTEMPTS,
                sanitize(e.getMessage()));
          }
        }
      }
    }
  }

  private void paceSend() {
    synchronized (rateLimitLock) {
      final long now = clock.millis();
      final long waitTime = nextAllowedTime - now;
      if (waitTime > 0) {
        sleeper.sleep(Duration.ofMillis(waitTime));
      }
      nextAllowedTime = clock.millis() + MIN_INTERVAL_MILLIS;
    }
  }

  private String formatMessage(Notification n) {
    final StringBuilder sb = new StringBuilder();
    sb.append('[')
        .append(n.severity())
        .append("] ")
        .append(n.type())
        .append('\n')
        .append(n.title())
        .append('\n')
        .append(n.body());

    if (n.cause() != null && n.cause().getMessage() != null) {
      sb.append("\nCause: ").append(n.cause().getMessage());
    }

    String result = sb.toString();
    if (result.length() > MAX_MESSAGE_LENGTH) {
      result =
          result.substring(0, MAX_MESSAGE_LENGTH - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }
    return result;
  }

  private String sanitize(String message) {
    if (message == null) {
      return "";
    }
    return message.replace(props.botToken(), "[REDACTED]");
  }
}
