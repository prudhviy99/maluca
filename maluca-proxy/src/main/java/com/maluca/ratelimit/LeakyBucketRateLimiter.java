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
 * Policing leaky bucket: a virtual queue drains at a constant rate; requests
 * that don't fit are rejected immediately. Output toward the upstream is
 * perfectly smooth — use when the protected resource hates bursts (DB
 * write paths, third-party APIs with strict pacing).
 */
@Component
public class LeakyBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script = LuaScripts.listReturning("leaky_bucket");

    private final ReactiveStringRedisTemplate redis;

    public LeakyBucketRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.LEAKY_BUCKET;
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return redis.execute(script,
                        List.of("maluca:rl:lb:" + key),
                        List.of(String.valueOf(config.ratePerSecond()),
                                String.valueOf(config.burst())))
                .next()
                .map(raw -> RateLimiterSupport.toDecision(raw, config.burst()));
    }
}
