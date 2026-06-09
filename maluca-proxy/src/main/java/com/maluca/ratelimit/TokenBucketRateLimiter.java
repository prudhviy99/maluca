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
 * Continuous refill at {@code ratePerSecond} up to {@code burst} capacity.
 * Permits short bursts while enforcing the long-run average — the right
 * default for public API quotas. {@code current} in the decision is tokens
 * remaining, not a request count.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script = LuaScripts.listReturning("token_bucket");

    private final ReactiveStringRedisTemplate redis;

    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitAlgorithm algorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public Mono<LimitDecision> check(String key, RateLimitConfig config) {
        return redis.execute(script,
                        List.of("maluca:rl:tb:" + key),
                        List.of(String.valueOf(config.ratePerSecond()),
                                String.valueOf(config.burst()),
                                "1"))
                .next()
                .map(raw -> RateLimiterSupport.toDecision(raw, config.burst()));
    }
}
