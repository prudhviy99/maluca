package com.maluca.state;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the degradation tier on /actuator/health. The breaker being open
 * is reported as a degraded-but-up state (not DOWN): Maluca is still serving
 * traffic per fail-mode policy, which is the whole point of degrading
 * gracefully.
 */
@Component("redisBreaker")
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisCircuitBreaker breaker;

    public RedisHealthIndicator(RedisCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public Health health() {
        DegradationState tier = breaker.isOpen()
                ? DegradationState.PASSTHROUGH
                : DegradationState.FULL;
        // Always report UP: a degraded Maluca is still serving traffic per
        // fail-mode policy, and we must not let a liveness probe restart an
        // instance that is correctly riding out a Redis outage. The detail
        // fields (and the maluca_redis_errors metric) carry the real state.
        return Health.up()
                .withDetail("breakerState", breaker.state())
                .withDetail("degradation", tier.name())
                .withDetail("degraded", breaker.isOpen())
                .build();
    }
}
