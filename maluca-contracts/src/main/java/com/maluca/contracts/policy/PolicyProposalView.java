package com.maluca.contracts.policy;

import java.time.Instant;
import java.util.UUID;

/** Auditable state of a route-scoped remediation proposal. */
public record PolicyProposalView(
        UUID id,
        UUID incidentId,
        UUID reportId,
        Instant reportCreatedAt,
        Instant createdAt,
        String createdBy,
        PolicyPatch patch,
        String proposalSha256,
        String policySha256,
        String targetPolicySha256,
        String status,
        Instant approvedAt,
        Instant appliedAt,
        String failure) {
}
