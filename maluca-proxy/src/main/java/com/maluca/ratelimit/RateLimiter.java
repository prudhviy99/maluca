package com.maluca.ratelimit;

import com.maluca.model.LimitDecision;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;

import reactor.core.publisher.Mono;

/**
 * Common contract for all rate-limit algorithms. Implementations are Redis
 * Lua scripts so a decision is atomic across all proxy instances sharing the
 * Redis — no double-admit races.
 */
public interface RateLimiter {

    RateLimitAlgorithm algorithm();

    Mono<LimitDecision> check(String key, RateLimitConfig config);
}
