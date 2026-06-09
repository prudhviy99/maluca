package com.maluca.policy;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.maluca.config.MalucaProperties.Identity.KeyStrategy;
import com.maluca.model.RateLimitAlgorithm;

/**
 * On-disk shape of a policy (policies.yml). Everything except {@code name}
 * and {@code route} is optional and falls back to global config.
 */
public record PolicyDefinition(
        String name,
        String route,
        Mode mode,
        List<String> tiers,
        KeyStrategy keying,
        @JsonProperty("rate-limit") RateLimitSpec rateLimit,
        BandsSpec bands,
        List<String> allowlist,
        List<String> denylist,
        @JsonProperty("fail-mode") FailMode failMode) {

    public enum Mode {
        /** Actions execute. */
        ENFORCE,
        /** Pipeline runs and logs; executed action is capped at OBSERVE. */
        OBSERVE,
        /** Like OBSERVE but flagged for tuning dashboards (would-have metrics). */
        DRY_RUN
    }

    /** What to do when Redis is unavailable (Phase 9 wiring). */
    public enum FailMode { FAIL_OPEN, FAIL_CLOSED }

    public record RateLimitSpec(
            RateLimitAlgorithm algorithm,
            long limit,
            @JsonProperty("window-seconds") long windowSeconds,
            @JsonProperty("rate-per-second") double ratePerSecond,
            long burst) {
    }

    public record BandsSpec(
            @JsonProperty("observe-min") Integer observeMin,
            @JsonProperty("soft-limit-min") Integer softLimitMin,
            @JsonProperty("hard-limit-min") Integer hardLimitMin,
            @JsonProperty("challenge-min") Integer challengeMin,
            @JsonProperty("block-min") Integer blockMin) {
    }

    public record PolicyFile(List<PolicyDefinition> policies) {
    }
}
