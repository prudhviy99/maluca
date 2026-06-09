package com.maluca.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.maluca.model.LimitDecision;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;

import reactor.core.publisher.Flux;

/**
 * Behavioral tests for the five Lua limiters against a real Redis
 * (localhost:6379 or REDIS_HOST/REDIS_PORT). Skipped when Redis is not
 * reachable, so plain unit-test runs stay green without infrastructure.
 *
 * The concurrency tests are the important ones: they assert the no-double-admit
 * guarantee that justifies doing this in Lua at all.
 */
class RateLimiterRedisTest {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        assumeTrue(redisReachable(), "Redis not reachable at " + HOST + ":" + PORT + " — skipping");
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        factory.start();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String freshKey() {
        return "test:" + UUID.randomUUID();
    }

    // ── Fixed window ──────────────────────────────────────────────────────────

    @Test
    void fixedWindowEnforcesLimit() {
        var limiter = new FixedWindowRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.FIXED_WINDOW, 5, 60);
        String key = freshKey();

        long allowed = checkN(limiter, key, cfg, 10);
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void fixedWindowIsAtomicUnderConcurrency() {
        var limiter = new FixedWindowRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.FIXED_WINDOW, 50, 60);
        String key = freshKey();

        long allowed = checkConcurrently(limiter, key, cfg, 200);
        assertThat(allowed).isEqualTo(50);
    }

    @Test
    void fixedWindowReportsRetryAfterWithinWindow() {
        var limiter = new FixedWindowRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.FIXED_WINDOW, 1, 60);
        String key = freshKey();

        limiter.check(key, cfg).block();
        LimitDecision denied = limiter.check(key, cfg).block();

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    // ── Sliding window counter ────────────────────────────────────────────────

    @Test
    void slidingWindowCounterEnforcesLimit() {
        var limiter = new SlidingWindowCounterRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, 5, 60);
        String key = freshKey();

        long allowed = checkN(limiter, key, cfg, 10);
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void slidingWindowCounterIsAtomicUnderConcurrency() {
        var limiter = new SlidingWindowCounterRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, 50, 60);
        String key = freshKey();

        long allowed = checkConcurrently(limiter, key, cfg, 200);
        assertThat(allowed).isEqualTo(50);
    }

    // ── Sliding window log ────────────────────────────────────────────────────

    @Test
    void slidingWindowLogIsExact() {
        var limiter = new SlidingWindowLogRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_LOG, 7, 60);
        String key = freshKey();

        long allowed = checkN(limiter, key, cfg, 20);
        assertThat(allowed).isEqualTo(7);
    }

    @Test
    void slidingWindowLogIsAtomicUnderConcurrency() {
        var limiter = new SlidingWindowLogRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_LOG, 25, 60);
        String key = freshKey();

        long allowed = checkConcurrently(limiter, key, cfg, 150);
        assertThat(allowed).isEqualTo(25);
    }

    @Test
    void slidingWindowLogRefillsAsEntriesAge() throws InterruptedException {
        var limiter = new SlidingWindowLogRateLimiter(redis);
        var cfg = RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_LOG, 2, 1);
        String key = freshKey();

        assertThat(checkN(limiter, key, cfg, 4)).isEqualTo(2);
        Thread.sleep(1100); // let the 1s window slide past both entries
        assertThat(limiter.check(key, cfg).block().allowed()).isTrue();
    }

    // ── Token bucket ──────────────────────────────────────────────────────────

    @Test
    void tokenBucketAllowsBurstThenPolices() {
        var limiter = new TokenBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.TOKEN_BUCKET, 1, 10);
        String key = freshKey();

        long allowed = checkN(limiter, key, cfg, 20);
        // full burst of 10, plus possibly 1 refilled token while draining
        assertThat(allowed).isBetween(10L, 11L);
    }

    @Test
    void tokenBucketRefillsOverTime() throws InterruptedException {
        var limiter = new TokenBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.TOKEN_BUCKET, 10, 5);
        String key = freshKey();

        checkN(limiter, key, cfg, 10); // drain
        assertThat(limiter.check(key, cfg).block().allowed()).isFalse();
        Thread.sleep(300); // ~3 tokens refill at 10/s
        assertThat(limiter.check(key, cfg).block().allowed()).isTrue();
    }

    @Test
    void tokenBucketIsAtomicUnderConcurrency() {
        var limiter = new TokenBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.TOKEN_BUCKET, 0.001, 30);
        String key = freshKey();

        long allowed = checkConcurrently(limiter, key, cfg, 200);
        assertThat(allowed).isEqualTo(30);
    }

    // ── Leaky bucket ──────────────────────────────────────────────────────────

    @Test
    void leakyBucketRejectsWhenFull() {
        var limiter = new LeakyBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.LEAKY_BUCKET, 0.001, 5);
        String key = freshKey();

        long allowed = checkN(limiter, key, cfg, 15);
        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void leakyBucketDrainsOverTime() throws InterruptedException {
        var limiter = new LeakyBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.LEAKY_BUCKET, 10, 3);
        String key = freshKey();

        checkN(limiter, key, cfg, 5); // fill to capacity 3
        assertThat(limiter.check(key, cfg).block().allowed()).isFalse();
        Thread.sleep(250); // ~2.5 requests drain at 10/s
        assertThat(limiter.check(key, cfg).block().allowed()).isTrue();
    }

    @Test
    void leakyBucketIsAtomicUnderConcurrency() {
        var limiter = new LeakyBucketRateLimiter(redis);
        var cfg = RateLimitConfig.bucket(RateLimitAlgorithm.LEAKY_BUCKET, 0.001, 40);
        String key = freshKey();

        long allowed = checkConcurrently(limiter, key, cfg, 200);
        assertThat(allowed).isEqualTo(40);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static long checkN(RateLimiter limiter, String key, RateLimitConfig cfg, int n) {
        long allowed = 0;
        for (int i = 0; i < n; i++) {
            if (limiter.check(key, cfg).block(Duration.ofSeconds(5)).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    private static long checkConcurrently(RateLimiter limiter, String key, RateLimitConfig cfg, int n) {
        return Flux.range(0, n)
                .flatMap(i -> limiter.check(key, cfg), 64)
                .filter(LimitDecision::allowed)
                .count()
                .block(Duration.ofSeconds(30));
    }
}
