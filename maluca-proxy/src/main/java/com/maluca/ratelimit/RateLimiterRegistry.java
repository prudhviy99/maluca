package com.maluca.ratelimit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;
import com.maluca.model.LimitDecision;

import reactor.core.publisher.Mono;

/**
 * Routes a check to the algorithm the policy asked for. Registered as the
 * primary {@link RateLimiter} so injection points that don't care about the
 * algorithm get config-driven dispatch for free.
 */
@Component
@Primary
public class RateLimiterRegistry implements RateLimiter {

    private final Map<RateLimitAlgorithm, RateLimiter> limiters = new EnumMap<>(RateLimitAlgorithm.class);

    public RateLimiterRegistry(List<RateLimiter> all) {
        for (RateLimiter limiter : all) {
            if (limiter != this) {
                limiters.put(limiter.algorithm(), limiter);
            }
        }
    }

    public RateLimiter get(RateLimitAlgorithm algorithm) {
        RateLimiter limiter = limiters.get(algorithm);
        if (limiter == null) {
            throw new IllegalArgumentException("No limiter registered for " + algorithm);
        }
        return limiter;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return null; // dispatcher, not a concrete algorithm
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return get(config.algorithm()).check(key, config);
    }
}
