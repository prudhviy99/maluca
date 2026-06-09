package com.maluca.ratelimit;

import java.util.List;

import com.maluca.model.LimitDecision;

/** Shared decoding of the {allowed, current, retryAfter} Lua return convention. */
final class RateLimiterSupport {

    private RateLimiterSupport() {
    }

    @SuppressWarnings("rawtypes")
    static LimitDecision toDecision(List raw, long limit) {
        if (raw == null || raw.size() < 3) {
            // A malformed reply must not take down the request path.
            return LimitDecision.allowedNoLimit();
        }
        boolean allowed = asLong(raw.get(0)) == 1;
        return new LimitDecision(allowed, asLong(raw.get(1)), limit, asLong(raw.get(2)));
    }

    static long asLong(Object value) {
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
