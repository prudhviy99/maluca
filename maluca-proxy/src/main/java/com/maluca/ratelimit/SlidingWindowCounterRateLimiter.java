package com.maluca.ratelimit;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.maluca.model.LimitDecision;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;
import com.maluca.state.LuaScripts;

import reactor.core.publisher.Mono;

/**
 * Weights the previous window by its remaining overlap — smooths the fixed
 * window's boundary burst with the same O(1) memory. The industry default
 * (Cloudflare uses this shape for most limits).
 */
@Component
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script = LuaScripts.listReturning("sliding_window_counter");

    private final ReactiveStringRedisTemplate redis;

    public SlidingWindowCounterRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW_COUNTER;
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return redis.execute(script,
                        List.of("maluca:rl:swc:" + key),
                        List.of(String.valueOf(config.limit()), String.valueOf(config.windowSeconds())))
                .next()
                .map(raw -> RateLimiterSupport.toDecision(raw, config.limit()));
    }
}
