package com.maluca.triage.decision;

import java.time.Instant;

/** All fields are optional except the already-clamped limit. */
public record DecisionQuery(
        String policyName,
        String clientKey,
        String action,
        Instant from,
        Instant to,
        int limit) {
}
