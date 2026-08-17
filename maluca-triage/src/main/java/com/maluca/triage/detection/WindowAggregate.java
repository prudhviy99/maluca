package com.maluca.triage.detection;

/** Deterministic inputs to the anomaly rules for one bounded policy name. */
public record WindowAggregate(
        String policyName,
        String policyRoute,
        long total,
        long mitigated,
        long challengeOrBlock,
        long redisErrors,
        double meanScore,
        int maxScore,
        long distinctClients,
        long distinctPaths,
        long baselineTotal,
        long baselineMitigated) {

    public double mitigationShare() {
        return total == 0 ? 0 : (double) mitigated / total;
    }

    public double baselineMitigationShare() {
        return baselineTotal == 0 ? 0 : (double) baselineMitigated / baselineTotal;
    }
}
