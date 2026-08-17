package com.maluca.triage.incident;

import java.time.Instant;
import java.util.UUID;

import com.maluca.contracts.incident.IncidentView;

/** A fenced claim: only the holder of {@code leaseId} may commit triage output. */
public record IncidentClaim(
        IncidentView incident,
        UUID leaseId,
        int attempt,
        Instant claimedAt) {
}
