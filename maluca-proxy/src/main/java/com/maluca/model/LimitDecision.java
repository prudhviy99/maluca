package com.maluca.model;

/** Result of a rate-limit algorithm evaluation. */
public record LimitDecision(
        boolean allowed,
        long current,
        long limit,
        long retryAfterSeconds) {

    public static LimitDecision allowedNoLimit() {
        return new LimitDecision(true, 0, Long.MAX_VALUE, 0);
    }
}
