package com.maluca.policy;

import java.util.Set;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

import com.maluca.config.MalucaProperties;
import com.maluca.config.MalucaProperties.Identity.KeyStrategy;
import com.maluca.model.RateLimitConfig;
import com.maluca.policy.PolicyDefinition.FailMode;
import com.maluca.policy.PolicyDefinition.Mode;
import com.maluca.util.CidrSet;

/**
 * A policy compiled for the hot path: parsed route pattern, resolved CIDR
 * sets, concrete rate-limit config. Immutable — the registry swaps whole
 * lists atomically, so a request never sees a half-reloaded policy.
 */
public record CompiledPolicy(
        String name,
        PathPattern pattern,
        Set<String> tiers,
        Mode mode,
        KeyStrategy keying,
        RateLimitConfig rateLimit,
        MalucaProperties.Bands bands,
        CidrSet allowlist,
        CidrSet denylist,
        FailMode failMode) {

    public boolean matches(PathContainer path, String tier) {
        if (!tiers.isEmpty() && !tiers.contains(tier)) {
            return false;
        }
        return pattern.matches(path);
    }

    public boolean isDryRun() {
        return mode == Mode.DRY_RUN;
    }
}
