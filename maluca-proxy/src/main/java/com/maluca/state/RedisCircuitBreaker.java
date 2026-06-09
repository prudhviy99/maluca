package com.maluca.state;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;
import com.maluca.metrics.MalucaMetrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Mono;

/**
 * Guards every Redis call on the decision path. After enough failures the
 * breaker opens and calls short-circuit instantly instead of piling up on a
 * dead/slow Redis (a stall is worse than an outage — it consumes threads).
 *
 * <p>Each guarded call also carries a hard timeout, so a Redis that accepts
 * connections but answers slowly is treated as a failure. When the breaker
 * is open or a call fails, the caller decides what to do via fallback —
 * which is where per-route fail-open vs fail-closed lives.
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    private final CircuitBreaker breaker;
    private final Duration callTimeout;
    private final MalucaMetrics metrics;

    public RedisCircuitBreaker(MalucaProperties properties, MalucaMetrics metrics) {
        MalucaProperties.Resilience cfg = properties.resilience();
        this.callTimeout = Duration.ofMillis(cfg.redisTimeoutMs());
        this.metrics = metrics;

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(cfg.failureRateThreshold())
                .slowCallRateThreshold(cfg.failureRateThreshold())
                .slowCallDurationThreshold(callTimeout)
                .waitDurationInOpenState(Duration.ofSeconds(cfg.openStateSeconds()))
                .slidingWindowSize(cfg.slidingWindowSize())
                .minimumNumberOfCalls(cfg.minimumCalls())
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        this.breaker = CircuitBreaker.of("redis", config);
        this.breaker.getEventPublisher().onStateTransition(e ->
                log.warn("redis_breaker_state from={} to={}",
                        e.getStateTransition().getFromState(), e.getStateTransition().getToState()));
    }

    /**
     * Runs a Redis call through the breaker + timeout. On any failure (open
     * breaker, timeout, error) emits {@code fallback} instead of erroring, so
     * the request pipeline never dies because Redis did.
     */
    public <T> Mono<T> run(Mono<T> redisCall, T fallback) {
        return redisCall
                .timeout(callTimeout)
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .onErrorResume(error -> {
                    metrics.incrementRedisErrors();
                    return Mono.justOrEmpty(fallback);
                });
    }

    public boolean isOpen() {
        return breaker.getState() == CircuitBreaker.State.OPEN;
    }

    public String state() {
        return breaker.getState().name();
    }
}
