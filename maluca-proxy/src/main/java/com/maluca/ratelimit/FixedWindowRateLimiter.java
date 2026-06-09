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
 * Counts requests in clock-aligned windows. O(1) memory per client. Its
 * weakness is the boundary burst: a client can do 2x the limit straddling a
 * window edge. Cheap and predictable — the right default for an MVP.
 */
@Component
public class FixedWindowRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script = LuaScripts.listReturning("fixed_window");

    private final ReactiveStringRedisTemplate redis;

    public FixedWindowRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.FIXED_WINDOW;
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return redis.execute(script,
                        List.of("maluca:rl:fw:" + key),
                        List.of(String.valueOf(config.limit()), String.valueOf(config.windowSeconds())))
                .next()
                .map(raw -> RateLimiterSupport.toDecision(raw, config.limit()));
    }
}
