package com.maluca.contracts.triage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.runbook.RunbookChunkView;

/** Persisted, externally safe projection of a model report. */
public record TriageReportView(
        UUID id,
        UUID incidentId,
        Instant createdAt,
        String model,
        String promptVersion,
        Classification classification,
        Confidence confidence,
        String summary,
        List<EvidenceReference> evidence,
        List<Citation> citations,
        List<RunbookChunkView> retrievedChunks,
        PolicyPatch proposedPatch,
        boolean valid,
        List<String> validationErrors) {

    public TriageReportView {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        citations = citations == null ? List.of() : List.copyOf(citations);
        retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
