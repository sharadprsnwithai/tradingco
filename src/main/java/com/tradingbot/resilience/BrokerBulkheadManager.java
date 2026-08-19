package com.tradingbot.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Manages isolated In-JVM Thread Pool Bulkheads, Rate Limiters,
 * and Circuit Breakers for each broker to guarantee total fault isolation.
 */
@Component
public class BrokerBulkheadManager {

    private final Scheduler kiteScheduler;
    private final Scheduler shoonyaScheduler;
    private final CircuitBreaker kiteCircuitBreaker;
    private final CircuitBreaker shoonyaCircuitBreaker;
    private final RateLimiter kiteRateLimiter;
    private final RateLimiter shoonyaRateLimiter;

    public BrokerBulkheadManager() {
        // Dedicated Bounded Elastic Schedulers per broker
        this.kiteScheduler = Schedulers.newBoundedElastic(10, 1000, "kite-bulkhead-pool");
        this.shoonyaScheduler = Schedulers.newBoundedElastic(10, 1000, "shoonya-bulkhead-pool");

        // Circuit Breaker Configurations
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();

        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(cbConfig);
        this.kiteCircuitBreaker = cbRegistry.circuitBreaker("kite");
        this.shoonyaCircuitBreaker = cbRegistry.circuitBreaker("shoonya");

        // Rate Limiter Configurations (Kite: 3 req/sec, Shoonya: 5 req/sec)
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();
        this.kiteRateLimiter = rlRegistry.rateLimiter("kite", RateLimiterConfig.custom()
            .limitForPeriod(3)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(2))
            .build());

        this.shoonyaRateLimiter = rlRegistry.rateLimiter("shoonya", RateLimiterConfig.custom()
            .limitForPeriod(5)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(2))
            .build());
    }

    /**
     * Executes a Kite reactive operation inside Kite's isolated bulkhead scheduler,
     * protected by Kite's CircuitBreaker and RateLimiter with a 2.5s execution timeout.
     */
    public <T> Mono<T> executeKite(Mono<T> operation) {
        return operation
            .subscribeOn(kiteScheduler)
            .transformDeferred(CircuitBreakerOperator.of(kiteCircuitBreaker))
            .transformDeferred(RateLimiterOperator.of(kiteRateLimiter))
            .timeout(Duration.ofMillis(2500));
    }

    /**
     * Executes a Shoonya reactive operation inside Shoonya's isolated bulkhead scheduler,
     * protected by Shoonya's CircuitBreaker and RateLimiter with a 2.5s execution timeout.
     */
    public <T> Mono<T> executeShoonya(Mono<T> operation) {
        return operation
            .subscribeOn(shoonyaScheduler)
            .transformDeferred(CircuitBreakerOperator.of(shoonyaCircuitBreaker))
            .transformDeferred(RateLimiterOperator.of(shoonyaRateLimiter))
            .timeout(Duration.ofMillis(2500));
    }

    /**
     * Returns the dedicated bounded-elastic scheduler for Kite broker operations.
     *
     * @return the Kite bulkhead scheduler
     */
    public Scheduler getKiteScheduler() {
        return kiteScheduler;
    }

    /**
     * Returns the dedicated bounded-elastic scheduler for Shoonya broker operations.
     *
     * @return the Shoonya bulkhead scheduler
     */
    public Scheduler getShoonyaScheduler() {
        return shoonyaScheduler;
    }

    /**
     * Returns the circuit breaker instance governing Kite broker calls.
     *
     * @return the Kite circuit breaker
     */
    public CircuitBreaker getKiteCircuitBreaker() {
        return kiteCircuitBreaker;
    }

    /**
     * Returns the circuit breaker instance governing Shoonya broker calls.
     *
     * @return the Shoonya circuit breaker
     */
    public CircuitBreaker getShoonyaCircuitBreaker() {
        return shoonyaCircuitBreaker;
    }

    /**
     * Disposes both broker bulkhead schedulers on application shutdown.
     */
    @PreDestroy
    public void dispose() {
        kiteScheduler.dispose();
        shoonyaScheduler.dispose();
    }
}
