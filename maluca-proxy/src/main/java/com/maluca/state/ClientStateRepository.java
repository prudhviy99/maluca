package com.maluca.state;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.maluca.model.ClientState;
import com.maluca.model.MitigationAction;

import reactor.core.publisher.Mono;

/**
 * All per-client behavioral state lives here, keyed by composite client key.
 * One request costs exactly one Redis round trip on the read+update path.
 */
@Repository
public class ClientStateRepository {

    public static final String PREFIX = "maluca:";

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> collectStateScript = LuaScripts.listReturning("collect_state");

    private final ReactiveStringRedisTemplate redis;
    private final RedisCircuitBreaker breaker;

    public ClientStateRepository(ReactiveStringRedisTemplate redis, RedisCircuitBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    /**
     * Records this request into all rolling windows and returns the updated
     * snapshot. Guarded by the breaker: if Redis is unavailable this yields
     * {@link ClientState#EMPTY} (no state ⇒ a benign score), and the
     * fail-open/closed decision is made downstream from the empty state.
     */
    public Mono<ClientState> collect(String clientKey, String path, boolean sensitive) {
        List<String> keys = List.of(
                PREFIX + "cnt10:" + clientKey,
                PREFIX + "cnt60:" + clientKey,
                PREFIX + "cnt300:" + clientKey,
                PREFIX + "cnt3600:" + clientKey,
                PREFIX + "paths:" + clientKey,
                PREFIX + "sens:" + clientKey,
                PREFIX + "sticky:" + clientKey,
                PREFIX + "4xx:" + clientKey);
        Mono<ClientState> call = redis.execute(collectStateScript, keys,
                        List.of(path, sensitive ? "1" : "0", "30", "60"))
                .next()
                .map(ClientStateRepository::toClientState);
        return breaker.run(call, ClientState.EMPTY);
    }

    public boolean redisHealthy() {
        return !breaker.isOpen();
    }

    /**
     * Pins a client to an action for a TTL (hysteresis): once escalated, the
     * client stays escalated even if its score drops, preventing flapping.
     */
    public Mono<Boolean> pinAction(String clientKey, MitigationAction action, Duration ttl) {
        return redis.opsForValue().set(PREFIX + "sticky:" + clientKey, action.name(), ttl);
    }

    /** Tracks upstream 4xx responses — a heavy 4xx ratio is a strong bot signal. */
    public Mono<Long> recordUpstream4xx(String clientKey) {
        String key = PREFIX + "4xx:" + clientKey;
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1
                        ? redis.expire(key, Duration.ofSeconds(60)).thenReturn(count)
                        : Mono.just(count));
    }

    @SuppressWarnings("rawtypes")
    private static ClientState toClientState(List raw) {
        if (raw == null || raw.size() < 9) {
            return ClientState.EMPTY;
        }
        return new ClientState(
                asLong(raw.get(0)),
                asLong(raw.get(1)),
                asLong(raw.get(2)),
                asLong(raw.get(3)),
                asLong(raw.get(4)),
                asLong(raw.get(5)),
                asLong(raw.get(6)),
                String.valueOf(raw.get(7)),
                asLong(raw.get(8)));
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
