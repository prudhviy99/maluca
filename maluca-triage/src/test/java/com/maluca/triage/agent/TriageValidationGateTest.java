package com.maluca.triage.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.contracts.triage.Citation;
import com.maluca.contracts.triage.EvidenceReference;
import com.maluca.contracts.triage.TriageResult;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.policy.PolicyPatchValidator;

class TriageValidationGateTest {

    private final varHolder holder = new varHolder();

    @Test
    void acceptsGroundedResultWithExactRetrievedCitation() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.HIGH,
                "A burst affected the api policy.",
                List.of(new EvidenceReference("totalDecisions", "120")),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")),
                patch());
        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "totalDecisions=120 policy=api").valid()).isTrue();
    }

    @Test
    void rejectsForgedCitationAndInventedEvidence() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.HIGH,
                "Burst.", List.of(new EvidenceReference("requests", "999999")),
                List.of(new Citation("invented", "fake.md", "Remediate")), null);
        var validation = holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "totalDecisions=120");
        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors()).anyMatch(error -> error.contains("not present"))
                .anyMatch(error -> error.contains("not retrieved"));
    }

    @Test
    void unknownMayHonestlyHaveNoCitationOrPatch() {
        var result = new TriageResult(Classification.UNKNOWN, Confidence.LOW,
                "Evidence is insufficient.", List.of(), List.of(), null);
        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "limited evidence").valid()).isTrue();
    }

    @Test
    void rejectsConfidentOrRemediatingUnknownResults() {
        var result = new TriageResult(Classification.UNKNOWN, Confidence.HIGH,
                "Claiming certainty without evidence.", List.of(), List.of(), patch());
        var validation = holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "limited evidence");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors())
                .anyMatch(error -> error.contains("requires LOW"))
                .anyMatch(error -> error.contains("cannot propose"));
    }

    @Test
    void rejectsNonUnknownResultWithoutDecisionEvidence() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.MEDIUM,
                "Generic diagnosis.", List.of(),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")), null);

        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "totalDecisions=120").errors())
                .anyMatch(error -> error.contains("grounded evidence"));
    }

    @Test
    void rejectsTrivialOrUnpairedBriefSubstringsAsEvidence() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.MEDIUM,
                "Unsupported diagnosis.",
                List.of(new EvidenceReference("policy", "1")),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")), null);

        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "policy=api unrelated_count=1").errors())
                .anyMatch(error -> error.contains("not present"));
    }

    @Test
    void acceptsAOneDigitValueWhenItsExactFactPairIsPresent() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.MEDIUM,
                "One client produced the burst.",
                List.of(new EvidenceReference("distinctClients", "1")),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")), null);

        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "distinctClients=1 totalDecisions=120").valid()).isTrue();
    }

    @Test
    void acceptsAnExactPairInsideABoundedFlatJsonMap() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.MEDIUM,
                "Block actions dominated the burst.",
                List.of(new EvidenceReference("action_counts.BLOCK", "190")),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")), null);

        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "incident={\"action_counts\":{\"BLOCK\":190,\"CHALLENGE\":120}}").valid()).isTrue();
    }

    @Test
    void rejectsADottedPairWhenTheParentMapDoesNotMatch() {
        var result = new TriageResult(Classification.BURST_FLOOD, Confidence.MEDIUM,
                "Unsupported map attribution.",
                List.of(new EvidenceReference("action_counts.BLOCK", "190")),
                List.of(new Citation("burst-flood.md#confirm", "burst-flood.md", "Confirm")), null);

        assertThat(holder.gate.validate(result, TriageTestFixtures.incident(), holder.chunks,
                "incident={\"unrelated_counts\":{\"BLOCK\":190}}").valid()).isFalse();
    }

    @Test
    void retainsOnlyExactGroundedEvidenceForSafeFinalization() {
        var evidence = List.of(
                new EvidenceReference("totalDecisions", "120"),
                new EvidenceReference("policy", "/api/**"),
                new EvidenceReference("action_counts.BLOCK", "190"));

        assertThat(holder.gate.retainGroundedEvidence(evidence,
                "totalDecisions=120 policy=api incident={\"action_counts\":{\"BLOCK\":190}}"))
                .extracting(EvidenceReference::fact)
                .containsExactly("totalDecisions", "action_counts.BLOCK");
    }

    private static PolicyPatch patch() {
        return new PolicyPatch("api", "/api/**", "DRY_RUN", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, "stage a conservative change");
    }

    private static final class varHolder {
        private final TriageValidationGate gate;
        private final List<RunbookChunkView> chunks = List.of(new RunbookChunkView(
                "burst-flood.md#confirm", "burst-flood.md", "Confirm", "Confirm traffic", .9));

        private varHolder() {
            var props = TriageTestFixtures.properties(Path.of("policies.yml"));
            gate = new TriageValidationGate(new PolicyPatchValidator(props), props);
        }
    }
}
