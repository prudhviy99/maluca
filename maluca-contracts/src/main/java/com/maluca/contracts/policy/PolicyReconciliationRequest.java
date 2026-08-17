package com.maluca.contracts.policy;

import java.util.UUID;

/**
 * Exact reviewed state required to reconcile an indeterminate apply. The
 * caller supplies no desired outcome; the service derives it from live state.
 */
public record PolicyReconciliationRequest(
        UUID proposalId,
        String expectedProposalSha256,
        String expectedBaselinePolicySha256,
        String expectedTargetPolicySha256,
        long expectedIncidentVersion) {
}
