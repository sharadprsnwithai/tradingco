package com.tradingbot.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerBulkheadIsolationTest {

    private BrokerBulkheadManager bulkheadManager;

    @BeforeEach
    void setUp() {
        bulkheadManager = new BrokerBulkheadManager();
    }

    @AfterEach
    void tearDown() {
        bulkheadManager.dispose();
    }

    @Test
    void testShoonyaFailureOrHangDoesNotBlockKiteExecution() {
        // Shoonya operation is slow/delayed (1.5 seconds)
        Mono<String> slowShoonyaOp = bulkheadManager.executeShoonya(
            Mono.just("SHOONYA_RESULT")
                .delayElement(Duration.ofMillis(1500))
        );

        // Kite operation is fast (10 milliseconds)
        Mono<String> fastKiteOp = bulkheadManager.executeKite(
            Mono.just("KITE_RESULT")
                .delayElement(Duration.ofMillis(10))
        );

        long start = System.currentTimeMillis();

        // Verify Kite completes promptly in sub-100ms regardless of Shoonya's execution
        StepVerifier.create(fastKiteOp)
            .expectNext("KITE_RESULT")
            .verifyComplete();

        long kiteDuration = System.currentTimeMillis() - start;
        assertTrue(kiteDuration < 1000, "Kite execution should be immediate and unblocked by Shoonya");

        // Verify Shoonya still succeeds in its own bulkhead pool
        StepVerifier.create(slowShoonyaOp)
            .expectNext("SHOONYA_RESULT")
            .verifyComplete();
    }

    @Test
    void testShoonyaTimeoutIsolation() {
        // Operation exceeding 2.5s timeout should trigger TimeoutException in Shoonya's pool
        Mono<String> hangingShoonyaOp = bulkheadManager.executeShoonya(
            Mono.just("HANGING_RESULT")
                .delayElement(Duration.ofMillis(3000))
        );

        // Kite operation runs normally in its own pool
        Mono<String> normalKiteOp = bulkheadManager.executeKite(
            Mono.just("KITE_OK")
        );

        StepVerifier.create(normalKiteOp)
            .expectNext("KITE_OK")
            .verifyComplete();

        StepVerifier.create(hangingShoonyaOp)
            .expectError()
            .verify();
    }

    @Test
    void testConcurrentExecutionAcrossBulkheads() {
        AtomicInteger kiteCount = new AtomicInteger();
        AtomicInteger shoonyaCount = new AtomicInteger();

        Mono<Void> combined = Mono.zip(
            bulkheadManager.executeKite(Mono.fromCallable(kiteCount::incrementAndGet)),
            bulkheadManager.executeShoonya(Mono.fromCallable(shoonyaCount::incrementAndGet))
        ).then();

        StepVerifier.create(combined)
            .verifyComplete();

        assertEquals(1, kiteCount.get());
        assertEquals(1, shoonyaCount.get());
    }
}
