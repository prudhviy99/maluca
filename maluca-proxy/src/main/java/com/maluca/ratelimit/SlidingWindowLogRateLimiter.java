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
 * Exact sliding window backed by a ZSET of admitted timestamps. Memory is
 * O(limit) per client — at limit=1M that's ~tens of MB of ZSET per client,
 * so this is for low-limit sensitive endpoints, never for bulk traffic.
 */
@Component
public class SlidingWindowLogRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script = LuaScripts.listReturning("sliding_window_log");

    private final ReactiveStringRedisTemplate redis;

    public SlidingWindowLogRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW_LOG;
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return redis.execute(script,
                        List.of("maluca:rl:swl:" + key),
                        List.of(String.valueOf(config.limit()), String.valueOf(config.windowSeconds())))
                .next()
                .map(raw -> RateLimiterSupport.toDecision(raw, config.limit()));
    }
}
