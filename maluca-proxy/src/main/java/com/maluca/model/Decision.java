package com.maluca.model;

import java.util.Map;

/** Final outcome of the mitigation pipeline for one request. */
public record Decision(
        MitigationAction action,
        int score,
        String reason,
        Map<String, Double> contributions,
        long retryAfterSeconds,
        boolean dryRun) {

    public static Decision of(MitigationAction action, int score, String reason,
                              Map<String, Double> contributions) {
        return new Decision(action, score, reason, contributions, 0, false);
    }

    public Decision withRetryAfter(long seconds) {
        return new Decision(action, score, reason, contributions, seconds, dryRun);
    }

    public Decision asDryRun() {
        return new Decision(action, score, reason, contributions, retryAfterSeconds, true);
    }
}
