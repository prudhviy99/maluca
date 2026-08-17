package com.maluca.contracts.policy;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed, route-scoped policy delta. Null fields mean "leave unchanged";
 * arbitrary YAML, file paths, and commands are intentionally impossible.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PolicyPatch(
        String policyName,
        String route,
        String mode,
        String keying,
        RateLimitPatch rateLimit,
        BandsPatch bands,
        List<String> addAllowlist,
        List<String> removeAllowlist,
        List<String> addDenylist,
        List<String> removeDenylist,
        String failMode,
        String rationale) {

    public PolicyPatch {
        addAllowlist = copy(addAllowlist);
        removeAllowlist = copy(removeAllowlist);
        addDenylist = copy(addDenylist);
        removeDenylist = copy(removeDenylist);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record RateLimitPatch(
            String algorithm,
            Long limit,
            Long windowSeconds,
            Double ratePerSecond,
            Long burst) {
    }

    public record BandsPatch(
            Integer observeMin,
            Integer softLimitMin,
            Integer hardLimitMin,
            Integer challengeMin,
            Integer blockMin) {
    }
}
