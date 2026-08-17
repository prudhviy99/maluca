package com.maluca.contracts.policy;

/** Deterministic result of comparing the live policy with recorded digests. */
public record PolicyReconciliationView(
        Outcome outcome,
        String observedPolicySha256,
        PolicyProposalView proposal) {

    public enum Outcome {
        TARGET_CONFIRMED,
        BASELINE_CONFIRMED
    }
}
