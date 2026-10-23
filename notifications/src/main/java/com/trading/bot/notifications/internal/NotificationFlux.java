package com.trading.bot.notifications.internal;

import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationPublisher;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Two-lane Reactor flux implementation of {@link NotificationPublisher}.
 *
 * <p>CRITICAL severity bypasses batching and delivers immediately. All other severities are batched
 * by size or time window. Bounded buffer drops oldest on overflow.
 */
@Component
public final class NotificationFlux implements NotificationPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationFlux.class);
  private static final long LOG_THROTTLE_MILLIS = 500L;

  private final Sinks.Many<Notification> batchSink;
  private final Sinks.Many<Notification> criticalSink;
  private final Counter droppedCounter;
  private final AtomicLong lastDropLogTime = new AtomicLong(0);

  @Autowired
  public NotificationFlux(
      NotificationTransport transport, PipelineProperties props, MeterRegistry registry) {
    this(transport, props, registry, Schedulers.boundedElastic(), Schedulers.parallel());
  }

  public NotificationFlux(
      NotificationTransport transport,
      PipelineProperties props,
      MeterRegistry registry,
      Scheduler deliveryScheduler,
      Scheduler timerScheduler) {
    Objects.requireNonNull(transport, "transport must not be null");
    Objects.requireNonNull(props, "props must not be null");
    Objects.requireNonNull(registry, "registry must not be null");
    Objects.requireNonNull(deliveryScheduler, "deliveryScheduler must not be null");
    Objects.requireNonNull(timerScheduler, "timerScheduler must not be null");

    this.droppedCounter = registry.counter("notifications.dropped");
    this.batchSink = Sinks.many().multicast().onBackpressureBuffer();
    this.criticalSink = Sinks.many().multicast().onBackpressureBuffer();

    this.criticalSink.asFlux().publishOn(deliveryScheduler).subscribe(transport::deliver);

    this.batchSink
        .asFlux()
        .onBackpressureBuffer(
            props.bufferSize(),
            dropped -> {
              droppedCounter.increment();
              final long now = System.currentTimeMillis();
              final long last = lastDropLogTime.get();
              if (now - last > LOG_THROTTLE_MILLIS && lastDropLogTime.compareAndSet(last, now)) {
                if (LOG.isWarnEnabled()) {
                  LOG.warn(
                      "Notification dropped due to backpressure overflow: {}", dropped.title());
                }
              }
            },
            BufferOverflowStrategy.DROP_OLDEST)
        .bufferTimeout(props.batchSize(), props.batchWindow(), timerScheduler)
        .publishOn(deliveryScheduler, 1)
        .subscribe(
            batch -> {
              for (final Notification n : batch) {
                transport.deliver(n);
              }
            });
  }

  @Override
  public void publish(Notification n) {
    if (n == null) {
      return;
    }
    if (n.severity() == Severity.CRITICAL) {
      criticalSink.tryEmitNext(n);
    } else {
      batchSink.tryEmitNext(n);
    }
  }
}
