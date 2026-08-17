package com.maluca.contracts.policy;

import java.util.UUID;

/** Human approval is bound to one immutable proposal and optimistic state versions. */
public record ApprovalRequest(
        UUID proposalId,
        String expectedProposalSha256,
        String expectedPolicySha256,
        long expectedIncidentVersion,
        String approvedBy) {
}
