package com.maluca.contracts.incident;

import java.time.Instant;
import java.util.UUID;

/** Stable REST/MCP representation of an incident. */
public record IncidentView(
        UUID id,
        Instant openedAt,
        Instant closedAt,
        String policyName,
        String policyRoute,
        IncidentTrigger trigger,
        IncidentStatus status,
        IncidentStats stats,
        long version,
        Instant triageClaimedAt,
        int triageAttempts,
        Instant triageNextAttemptAt,
        String triageFailure) {
}
