package com.maluca.triage.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.contracts.triage.Citation;
import com.maluca.contracts.triage.EvidenceReference;
import com.maluca.contracts.triage.TriageResult;
import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.policy.PolicyPatchValidator;

/** Code-enforced grounding, citation, size, and remediation policy gate. */
@Component
public class TriageValidationGate {

    private final PolicyPatchValidator patchValidator;
    private final TriageProperties.Agent config;

    public TriageValidationGate(PolicyPatchValidator patchValidator, TriageProperties properties) {
        this.patchValidator = patchValidator;
        this.config = properties.agent();
    }

    public ValidationResult validate(TriageResult result, IncidentView incident,
                                     List<RunbookChunkView> retrieved, String incidentBrief) {
        List<String> errors = new ArrayList<>();
        if (result == null) {
            return new ValidationResult(false, List.of("model result is null"));
        }
        if (result.classification() == null) {
            errors.add("classification is required");
        }
        if (result.confidence() == null) {
            errors.add("confidence is required");
        }
        if (result.summary() == null || result.summary().isBlank()) {
            errors.add("summary is required");
        } else if (wordCount(result.summary()) > config.maxSummaryWords()) {
            errors.add("summary exceeds " + config.maxSummaryWords() + " words");
        }
        if (result.evidence().size() > config.maxEvidenceItems()) {
            errors.add("too many evidence references");
        }
        if (result.classification() == Classification.UNKNOWN) {
            if (result.confidence() != Confidence.LOW) {
                errors.add("UNKNOWN classification requires LOW confidence");
            }
            if (result.proposedPatch() != null) {
                errors.add("UNKNOWN classification cannot propose a policy patch");
            }
        } else if (result.classification() != null && result.evidence().isEmpty()) {
            errors.add("non-UNKNOWN reports require at least one grounded evidence reference");
        }
        if (result.proposedPatch() != null
                && (result.evidence().isEmpty() || result.citations().isEmpty())) {
            errors.add("policy patches require grounded evidence and a runbook citation");
        }
        validateEvidence(result.evidence(), incidentBrief, errors);
        validateCitations(result, retrieved, errors);
        errors.addAll(patchValidator.validate(result.proposedPatch(), incident));
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    private static void validateEvidence(List<EvidenceReference> evidence, String brief, List<String> errors) {
        String normalizedBrief = normalize(brief);
        for (EvidenceReference reference : evidence) {
            if (reference == null || reference.fact() == null || reference.fact().isBlank()
                    || reference.value() == null || reference.value().isBlank()) {
                errors.add("evidence entries require fact and value");
                continue;
            }
            String normalizedFact = normalize(reference.fact());
            String normalizedValue = normalize(reference.value());
            if (normalizedFact.length() < 3 || normalizedFact.length() > 128
                    || normalizedValue.isEmpty() || normalizedValue.length() > 512) {
                errors.add("evidence fact/value is too short or too long to ground safely");
                continue;
            }
            // Require the claimed fact and value to appear as one field/value
            // pair in the selected-field brief. Independent global substrings
            // such as fact=clients,value=1 are not sufficient provenance.
            if (!containsGroundedPair(normalizedBrief, normalizedFact, normalizedValue)) {
                errors.add("evidence value is not present in the frozen incident brief: " + reference.value());
            }
        }
    }

    /**
     * Removes model claims that cannot be tied to an exact frozen field/value
     * pair. The caller must re-run the full validation gate on the result.
     */
    List<EvidenceReference> retainGroundedEvidence(List<EvidenceReference> evidence, String brief) {
        String normalizedBrief = normalize(brief);
        return evidence.stream().filter(reference -> {
            if (reference == null || reference.fact() == null || reference.fact().isBlank()
                    || reference.value() == null || reference.value().isBlank()) {
                return false;
            }
            String fact = normalize(reference.fact());
            String value = normalize(reference.value());
            return fact.length() >= 3 && fact.length() <= 128
                    && !value.isEmpty() && value.length() <= 512
                    && containsGroundedPair(normalizedBrief, fact, value);
        }).toList();
    }

    private static boolean containsGroundedPair(String brief, String fact, String value) {
        String jsonPair = "\"" + fact + "\":\"" + value + "\"";
        String numericPair = "\"" + fact + "\":" + value;
        String textPair = fact + "=" + value;
        return brief.contains(jsonPair) || brief.contains(numericPair) || brief.contains(textPair)
                || containsNestedJsonPair(brief, fact, value);
    }

    /** Accepts an exact pair in a bounded, flat JSON map such as action_counts.BLOCK=190. */
    private static boolean containsNestedJsonPair(String brief, String fact, String value) {
        String[] path = fact.split("\\.", -1);
        if (path.length != 2 || !safeJsonKey(path[0]) || !safeJsonKey(path[1])) {
            return false;
        }
        String objectMarker = "\"" + path[0] + "\":{";
        int objectStart = brief.indexOf(objectMarker);
        if (objectStart < 0) {
            return false;
        }
        int objectEnd = brief.indexOf('}', objectStart + objectMarker.length());
        if (objectEnd < 0 || objectEnd - objectStart > 4_096) {
            return false;
        }
        String object = brief.substring(objectStart, objectEnd + 1);
        String leaf = "\"" + path[1] + "\":";
        return object.contains(leaf + "\"" + value + "\"") || object.contains(leaf + value);
    }

    private static boolean safeJsonKey(String value) {
        return !value.isEmpty() && value.length() <= 128
                && value.chars().allMatch(character -> Character.isLetterOrDigit(character)
                        || character == '_');
    }

    private static void validateCitations(TriageResult result, List<RunbookChunkView> retrieved,
                                          List<String> errors) {
        Map<String, RunbookChunkView> allowed = new HashMap<>();
        for (RunbookChunkView chunk : retrieved) {
            allowed.put(chunk.chunkId(), chunk);
        }
        if (result.classification() != Classification.UNKNOWN && result.citations().isEmpty()) {
            errors.add("non-UNKNOWN reports require at least one runbook citation");
        }
        Set<String> seen = new HashSet<>();
        for (Citation citation : result.citations()) {
            if (citation == null || citation.chunkId() == null) {
                errors.add("citation requires chunkId, source, and heading");
                continue;
            }
            RunbookChunkView chunk = allowed.get(citation.chunkId());
            if (chunk == null) {
                errors.add("citation was not retrieved: " + citation.chunkId());
            } else if (!chunk.source().equals(citation.source()) || !chunk.heading().equals(citation.heading())) {
                errors.add("citation metadata does not match retrieved chunk: " + citation.chunkId());
            }
            if (!seen.add(citation.chunkId())) {
                errors.add("duplicate citation: " + citation.chunkId());
            }
        }
    }

    private static int wordCount(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
