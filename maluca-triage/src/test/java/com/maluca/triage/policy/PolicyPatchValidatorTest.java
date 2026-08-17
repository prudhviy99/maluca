package com.maluca.triage.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.triage.TriageTestFixtures;

class PolicyPatchValidatorTest {

    private final PolicyPatchValidator validator =
            new PolicyPatchValidator(TriageTestFixtures.properties(Path.of("policies.yml")));

    @Test
    void acceptsAConservativeRouteScopedModeChange() {
        PolicyPatch patch = patch("api", "/api/**", "DRY_RUN", null, null, "stage before enforcing");
        assertThat(validator.validate(patch, TriageTestFixtures.incident())).isEmpty();
    }

    @Test
    void rejectsWrongRouteAndNoOp() {
        PolicyPatch patch = patch("api", "/**", null, null, null, "wrong scope");
        assertThat(validator.validate(patch, TriageTestFixtures.incident()))
                .anyMatch(error -> error.contains("route"))
                .anyMatch(error -> error.contains("change at least one"));
    }

    @Test
    void rejectsNonMonotonicBands() {
        var bands = new PolicyPatch.BandsPatch(30, 60, 55, 75, 90);
        assertThat(validator.validate(patch("api", "/api/**", null, null, bands, "bad bands"),
                TriageTestFixtures.incident())).anyMatch(error -> error.contains("strictly increasing"));
    }

    @Test
    void enforcesAlgorithmSpecificFields() {
        var rate = new PolicyPatch.RateLimitPatch("TOKEN_BUCKET", 10L, 60L, -1.0, 0L);
        assertThat(validator.validate(patch("api", "/api/**", null, rate, null, "bad rate"),
                TriageTestFixtures.incident()))
                .anyMatch(error -> error.contains("ratePerSecond"))
                .anyMatch(error -> error.contains("burst"))
                .anyMatch(error -> error.contains("cannot set limit"));
    }

    @Test
    void rejectsInvalidCidrs() {
        PolicyPatch patch = new PolicyPatch("api", "/api/**", null, null, null, null,
                List.of(), List.of(), List.of("not-a-cidr"), List.of(), null, "block source");
        assertThat(validator.validate(patch, TriageTestFixtures.incident()))
                .anyMatch(error -> error.contains("invalid CIDR"));
    }

    @Test
    void acceptsLiteralIpv4AndIpv6ButNeverHostnames() {
        assertThat(PolicyPatchValidator.isCidr("192.0.2.0/24")).isTrue();
        assertThat(PolicyPatchValidator.isCidr("2001:db8::/32")).isTrue();
        assertThat(PolicyPatchValidator.isCidr("localhost/32")).isFalse();
        assertThat(PolicyPatchValidator.isCidr("example.com")).isFalse();
        assertThat(PolicyPatchValidator.isCidr("127.1/16")).isFalse();
        assertThat(PolicyPatchValidator.isCidr("999.0.0.1/24")).isFalse();
    }

    @Test
    void rejectsUnsafeNumericBoundsDuplicateNetworksAndControlCharacters() {
        var rate = new PolicyPatch.RateLimitPatch(
                "FIXED_WINDOW", 10_000_001L, 86_401L, null, null);
        PolicyPatch patch = new PolicyPatch("api", "/api/**", null, null, rate, null,
                List.of("192.0.2.0/24", "192.0.2.0/24"), List.of("192.0.2.0/24"),
                List.of("192.0.2.0/24"), List.of(), null, "bad\nrationale");

        assertThat(validator.validate(patch, TriageTestFixtures.incident()))
                .anyMatch(error -> error.contains("10000000"))
                .anyMatch(error -> error.contains("86400"))
                .anyMatch(error -> error.contains("duplicate"))
                .anyMatch(error -> error.contains("both added and removed"))
                .anyMatch(error -> error.contains("both allowlist and denylist"))
                .anyMatch(error -> error.contains("rationale"));
    }

    private static PolicyPatch patch(String name, String route, String mode,
                                     PolicyPatch.RateLimitPatch rate,
                                     PolicyPatch.BandsPatch bands, String rationale) {
        return new PolicyPatch(name, route, mode, null, rate, bands,
                List.of(), List.of(), List.of(), List.of(), null, rationale);
    }
}
