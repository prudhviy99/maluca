package com.maluca.model;

/**
 * Algorithm-agnostic rate limit configuration.
 *
 * <ul>
 *   <li>fixed window / sliding window: {@code limit} requests per {@code windowSeconds}</li>
 *   <li>token bucket: refill {@code ratePerSecond}, capacity {@code burst}</li>
 *   <li>leaky bucket (policing): drain {@code ratePerSecond}, queue depth {@code burst}</li>
 * </ul>
 */
public record RateLimitConfig(
        RateLimitAlgorithm algorithm,
        long limit,
        long windowSeconds,
        double ratePerSecond,
        long burst) {

    public static RateLimitConfig windowed(RateLimitAlgorithm algorithm, long limit, long windowSeconds) {
        return new RateLimitConfig(algorithm, limit, windowSeconds, 0, 0);
    }

    public static RateLimitConfig bucket(RateLimitAlgorithm algorithm, double ratePerSecond, long burst) {
        return new RateLimitConfig(algorithm, 0, 0, ratePerSecond, burst);
    }
}
