package com.maluca.contracts.triage;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.policy.PolicyPatch;

/** Strict structured-output contract for local model inference. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TriageResult(
        Classification classification,
        Confidence confidence,
        String summary,
        List<EvidenceReference> evidence,
        List<Citation> citations,
        PolicyPatch proposedPatch) {

    public TriageResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
