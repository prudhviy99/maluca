package com.maluca.mcp.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.mcp.TestProperties;

class PolicyPatchValidatorTest {

    private final PolicyPatchValidator validator = new PolicyPatchValidator(TestProperties.defaults());

    @Test
    void acceptsTypedRouteScopedPatch() {
        PolicyPatch patch = new PolicyPatch(
                "login", "/api/login", "DRY_RUN", "NETWORK",
                new PolicyPatch.RateLimitPatch("TOKEN_BUCKET", null, null, 10.0, 20L),
                new PolicyPatch.BandsPatch(20, 40, 60, 75, 90),
                List.of("192.0.2.0/24"), List.of(), List.of(), List.of(),
                "FAIL_OPEN", "Reduce false positives while monitoring the next window.");

        assertThatCode(() -> validator.validate(patch)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPathsAndContradictoryNetworkChanges() {
        PolicyPatch patch = new PolicyPatch(
                "login", "/api/../admin", null, null, null, null,
                List.of("192.0.2.1"), List.of("192.0.2.1"), List.of(), List.of(),
                null, "Unsafe route test");

        assertThatThrownBy(() -> validator.validate(patch))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("route");
    }

    @Test
    void rejectsNonMonotonicBands() {
        PolicyPatch patch = new PolicyPatch(
                "login", "/api/login", null, null, null,
                new PolicyPatch.BandsPatch(30, 20, null, null, null),
                List.of(), List.of(), List.of(), List.of(), null, "Invalid band ordering");

        assertThatThrownBy(() -> validator.validate(patch))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("increase in severity order");
    }

    @Test
    void rejectsInvalidNumericCidrs() {
        PolicyPatch patch = new PolicyPatch(
                "login", "/api/login", null, null, null, null,
                List.of("999.2.3.4/99"), List.of(), List.of(), List.of(),
                null, "Reject malformed address");

        assertThatThrownBy(() -> validator.validate(patch))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("invalid IP/CIDR");
    }

    @Test
    void rejectsNoOpAndAlgorithmSpecificRateLimitMismatches() {
        PolicyPatch noOp = new PolicyPatch(
                "login", "/api/login", null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, "No effective delta");
        assertThatThrownBy(() -> validator.validate(noOp))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("must change");

        PolicyPatch mismatch = new PolicyPatch(
                "login", "/api/login", null, null,
                new PolicyPatch.RateLimitPatch("FIXED_WINDOW", 10L, 60L, 5.0, null),
                null, List.of(), List.of(), List.of(), List.of(), null,
                "Reject fields from a different algorithm family");
        assertThatThrownBy(() -> validator.validate(mismatch))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("cannot set ratePerSecond");
    }

    @Test
    void approvalRequiresAnExactProposalIdAndBothDigests() {
        UUID proposalId = UUID.fromString("19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1");
        assertThatCode(() -> validator.validateApproval(new ApprovalRequest(
                proposalId, "b".repeat(64), "a".repeat(64), 4, "on-call")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateApproval(new ApprovalRequest(
                null, "b".repeat(64), "a".repeat(64), 4, "on-call")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("proposalId");
        assertThatThrownBy(() -> validator.validateApproval(new ApprovalRequest(
                proposalId, "not-a-digest", "a".repeat(64), 4, "on-call")))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("expectedProposalSha256");
    }
}
