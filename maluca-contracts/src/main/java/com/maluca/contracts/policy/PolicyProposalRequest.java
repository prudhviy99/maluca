package com.maluca.contracts.policy;

import java.util.UUID;

/** Agent-authored proposal; applying it remains a separate human action. */
public record PolicyProposalRequest(UUID incidentId, PolicyPatch patch) {
}
