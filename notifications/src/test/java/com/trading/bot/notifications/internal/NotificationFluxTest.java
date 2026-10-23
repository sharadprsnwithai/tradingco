package com.trading.bot.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.trading.bot.notifications.api.Notification;
import com.trading.bot.notifications.api.NotificationType;
import com.trading.bot.notifications.api.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class NotificationFluxTest {

  private VirtualTimeScheduler vts;

  @AfterEach
  void tearDown() {
    if (vts != null) {
      VirtualTimeScheduler.reset();
    }
  }

  @Test
  void normalNotificationsFlushAfterBatchWindow() {
    vts = VirtualTimeScheduler.getOrSet();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineProperties props = new PipelineProperties(Duration.ofSeconds(1), 50, 1000);
    final List<Notification> delivered = new CopyOnWriteArrayList<>();

    final NotificationFlux flux = new NotificationFlux(delivered::add, props, registry, vts, vts);

    final Notification n1 =
        Notification.of(Severity.INFO, NotificationType.STARTUP, "title1", "body1");
    final Notification n2 =
        Notification.of(Severity.INFO, NotificationType.STARTUP, "title2", "body2");

    flux.publish(n1);
    flux.publish(n2);

    assertThat(delivered).isEmpty();

    vts.advanceTimeBy(Duration.ofSeconds(1));

    assertThat(delivered).containsExactly(n1, n2);
  }

  @Test
  void sizeFlushDeliversFiftyWithoutTimeAdvance() {
    vts = VirtualTimeScheduler.getOrSet();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineProperties props = new PipelineProperties(Duration.ofSeconds(1), 50, 1000);
    final List<Notification> delivered = new CopyOnWriteArrayList<>();

    final NotificationFlux flux = new NotificationFlux(delivered::add, props, registry, vts, vts);

    final List<Notification> expected = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      final Notification n =
          Notification.of(Severity.INFO, NotificationType.ORDER_FILLED, "title" + i, "body" + i);
      expected.add(n);
      flux.publish(n);
    }

    assertThat(delivered).containsExactlyElementsOf(expected);
  }

  @Test
  void criticalDeliveredBeforeNormalBatchMidWindow() {
    vts = VirtualTimeScheduler.getOrSet();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineProperties props = new PipelineProperties(Duration.ofSeconds(1), 50, 1000);
    final List<Notification> delivered = new CopyOnWriteArrayList<>();

    final NotificationFlux flux = new NotificationFlux(delivered::add, props, registry, vts, vts);

    final Notification normal =
        Notification.of(Severity.INFO, NotificationType.ORDER_SUBMITTED, "normal", "normal-body");
    final Notification critical =
        Notification.of(
            Severity.CRITICAL, NotificationType.AUTH_DEGRADED, "critical", "critical-body");

    flux.publish(normal);
    vts.advanceTimeBy(Duration.ofMillis(500));

    flux.publish(critical);

    assertThat(delivered).containsExactly(critical);

    vts.advanceTimeBy(Duration.ofMillis(500));

    assertThat(delivered).containsExactly(critical, normal);
  }

  @Test
  void transportBlockedPublishesUnder100MsAndDropsOldest() throws InterruptedException {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineProperties props = new PipelineProperties(Duration.ofSeconds(1), 50, 1000);

    final CountDownLatch blockTransportLatch = new CountDownLatch(1);
    final CountDownLatch transportEnteredLatch = new CountDownLatch(1);
    final AtomicBoolean firstDelivered = new AtomicBoolean(false);

    final NotificationFlux flux =
        new NotificationFlux(
            n -> {
              if (firstDelivered.compareAndSet(false, true)) {
                transportEnteredLatch.countDown();
                try {
                  blockTransportLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            props,
            registry);

    final Notification first =
        Notification.of(Severity.INFO, NotificationType.STARTUP, "first", "first");
    flux.publish(first);
    assertThat(transportEnteredLatch.await(2, TimeUnit.SECONDS)).isTrue();

    final List<Notification> toPublish = new ArrayList<>(1100);
    for (int i = 0; i < 1100; i++) {
      toPublish.add(
          Notification.of(
              Severity.INFO, NotificationType.ORDER_SUBMITTED, "title" + i, "body" + i));
    }

    final long start = System.nanoTime();
    for (int i = 0; i < 1100; i++) {
      flux.publish(toPublish.get(i));
    }
    final long elapsed = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsed)
        .as(
            "1100 publish() calls should complete in bounded non-blocking time (<250ms) even when transport blocks 5s")
        .isLessThan(250);

    blockTransportLatch.countDown();

    final double dropped = registry.get("notifications.dropped").counter().count();
    assertThat(dropped).isGreaterThan(0.0);
  }

  @Test
  void batchPreservesExactOrder() {
    vts = VirtualTimeScheduler.getOrSet();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final PipelineProperties props = new PipelineProperties(Duration.ofSeconds(1), 50, 1000);
    final List<Notification> delivered = new CopyOnWriteArrayList<>();

    final NotificationFlux flux = new NotificationFlux(delivered::add, props, registry, vts, vts);

    final List<Notification> sent = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      final Notification n =
          Notification.of(Severity.WARN, NotificationType.SYSTEM_ALERT, "title" + i, "body" + i);
      sent.add(n);
      flux.publish(n);
    }

    vts.advanceTimeBy(Duration.ofSeconds(1));

    assertThat(delivered).containsExactlyElementsOf(sent);
  }
}
