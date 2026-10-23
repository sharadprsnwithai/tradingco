package com.trading.bot.notifications.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for notification batching and buffering.
 *
 * @param batchWindow duration to wait for filling a batch before flushing
 * @param batchSize maximum batch size before immediate flush
 * @param bufferSize bounded buffer capacity before dropping oldest notifications
 */
@ConfigurationProperties("notifications")
public record PipelineProperties(Duration batchWindow, int batchSize, int bufferSize) {

  private static final Duration DEFAULT_BATCH_WINDOW = Duration.ofSeconds(1);
  private static final int DEFAULT_BATCH_SIZE = 50;
  private static final int DEFAULT_BUFFER_SIZE = 1000;

  /** Canonical compact constructor with fallback defaults. */
  public PipelineProperties {
    if (batchWindow == null) {
      batchWindow = DEFAULT_BATCH_WINDOW;
    }
    if (batchSize <= 0) {
      batchSize = DEFAULT_BATCH_SIZE;
    }
    if (bufferSize <= 0) {
      bufferSize = DEFAULT_BUFFER_SIZE;
    }
  }

  /** Default constructor using tested defaults: 1s, 50, 1000. */
  public PipelineProperties() {
    this(DEFAULT_BATCH_WINDOW, DEFAULT_BATCH_SIZE, DEFAULT_BUFFER_SIZE);
  }
}
