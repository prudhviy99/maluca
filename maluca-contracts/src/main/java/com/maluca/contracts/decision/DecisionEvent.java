package com.maluca.contracts.decision;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Immutable, idempotent snapshot of one Maluca mitigation decision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionEvent(
        UUID eventId,
        Instant occurredAt,
        String clientKey,
        String method,
        String path,
        String policyName,
        String policyRoute,
        String policyMode,
        String tier,
        String computedAction,
        String executedAction,
        int score,
        String reason,
        Map<String, Double> contributions,
        boolean dryRun,
        String traceId) {

    public DecisionEvent {
        contributions = contributions == null ? Map.of() : Map.copyOf(contributions);
    }
}
