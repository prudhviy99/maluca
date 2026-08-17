package com.maluca.mcp.tool;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.ToolParam;

import com.maluca.contracts.policy.PolicyPatch;

/**
 * MCP input model for a partial policy delta.
 *
 * <p>The shared wire contract intentionally uses nullable fields, but its record
 * components have no schema annotations. Keeping this adapter local makes Spring
 * AI advertise optional patch fields correctly instead of requiring clients to
 * send every possible field.
 */
public record PolicyPatchInput(
        @ToolParam(description = "Existing incident policy name")
        String policyName,
        @ToolParam(description = "Existing incident route pattern")
        String route,
        @ToolParam(description = "Optional ENFORCE, OBSERVE, or DRY_RUN mode", required = false)
        String mode,
        @ToolParam(description = "Optional NETWORK, COMPOSITE, or FINGERPRINT key strategy", required = false)
        String keying,
        @ToolParam(description = "Optional partial rate-limit change", required = false)
        RateLimitInput rateLimit,
        @ToolParam(description = "Optional partial score-band change", required = false)
        BandsInput bands,
        @ToolParam(description = "CIDRs or IPs to add to the allowlist", required = false)
        List<String> addAllowlist,
        @ToolParam(description = "CIDRs or IPs to remove from the allowlist", required = false)
        List<String> removeAllowlist,
        @ToolParam(description = "CIDRs or IPs to add to the denylist", required = false)
        List<String> addDenylist,
        @ToolParam(description = "CIDRs or IPs to remove from the denylist", required = false)
        List<String> removeDenylist,
        @ToolParam(description = "Optional FAIL_OPEN or FAIL_CLOSED behavior", required = false)
        String failMode,
        @ToolParam(description = "Evidence-grounded reason for this exact delta")
        String rationale) {

    public PolicyPatch toContract() {
        return new PolicyPatch(
                policyName,
                route,
                upper(mode),
                upper(keying),
                rateLimit == null ? null : rateLimit.toContract(),
                bands == null ? null : bands.toContract(),
                addAllowlist,
                removeAllowlist,
                addDenylist,
                removeDenylist,
                upper(failMode),
                rationale);
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    public record RateLimitInput(
            @ToolParam(description = "Rate-limit algorithm", required = false)
            String algorithm,
            @ToolParam(description = "Request limit", required = false)
            Long limit,
            @ToolParam(description = "Window duration in seconds", required = false)
            Long windowSeconds,
            @ToolParam(description = "Token/leak rate per second", required = false)
            Double ratePerSecond,
            @ToolParam(description = "Token-bucket burst capacity", required = false)
            Long burst) {

        PolicyPatch.RateLimitPatch toContract() {
            return new PolicyPatch.RateLimitPatch(
                    upper(algorithm), limit, windowSeconds, ratePerSecond, burst);
        }
    }

    public record BandsInput(
            @ToolParam(description = "Observe threshold", required = false)
            Integer observeMin,
            @ToolParam(description = "Soft-limit threshold", required = false)
            Integer softLimitMin,
            @ToolParam(description = "Hard-limit threshold", required = false)
            Integer hardLimitMin,
            @ToolParam(description = "Challenge threshold", required = false)
            Integer challengeMin,
            @ToolParam(description = "Block threshold", required = false)
            Integer blockMin) {

        PolicyPatch.BandsPatch toContract() {
            return new PolicyPatch.BandsPatch(
                    observeMin, softLimitMin, hardLimitMin, challengeMin, blockMin);
        }
    }
}
