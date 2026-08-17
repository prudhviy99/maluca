package com.maluca.triage.decision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.maluca.contracts.decision.DecisionBatch;
import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.privacy.ClientKeyPseudonymizer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class DecisionIngestService {

    private static final Set<String> ACTIONS = Set.of(
            "ALLOW", "OBSERVE", "SOFT_LIMIT", "HARD_LIMIT", "CHALLENGE", "BLOCK");
    private static final Set<String> POLICY_MODES = Set.of("ENFORCE", "OBSERVE", "DRY_RUN");
    private static final double MAX_CONTRIBUTION_VALUE = 1_000_000_000d;

    private final DecisionRepository repository;
    private final ClientKeyPseudonymizer pseudonymizer;
    private final int maxBatchSize;
    private final int maxPathLength;
    private final Counter accepted;
    private final Counter duplicates;

    public DecisionIngestService(DecisionRepository repository, ClientKeyPseudonymizer pseudonymizer,
                                 TriageProperties properties, MeterRegistry meters) {
        this.repository = repository;
        this.pseudonymizer = pseudonymizer;
        this.maxBatchSize = properties.ingest().maxBatchSize();
        this.maxPathLength = properties.privacy().maxPathLength();
        this.accepted = Counter.builder("maluca.triage.ingest.accepted").register(meters);
        this.duplicates = Counter.builder("maluca.triage.ingest.duplicates").register(meters);
    }

    public IngestResult ingest(DecisionBatch batch) {
        if (batch == null || batch.events().isEmpty()) {
            return new IngestResult(0, 0);
        }
        if (batch.events().size() > maxBatchSize) {
            throw new IllegalArgumentException("batch contains more than " + maxBatchSize + " events");
        }
        List<DecisionEvent> sanitized = new ArrayList<>(batch.events().size());
        for (DecisionEvent event : batch.events()) {
            sanitized.add(sanitize(event));
        }
        int inserted = repository.insertBatch(sanitized);
        int duplicateCount = sanitized.size() - inserted;
        accepted.increment(inserted);
        duplicates.increment(duplicateCount);
        return new IngestResult(inserted, duplicateCount);
    }

    private DecisionEvent sanitize(DecisionEvent event) {
        if (event == null || event.eventId() == null || event.occurredAt() == null) {
            throw new IllegalArgumentException("eventId and occurredAt are required");
        }
        if (event.occurredAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new IllegalArgumentException("occurredAt is unreasonably far in the future");
        }
        String computed = normalizedAction(event.computedAction());
        String executed = normalizedAction(event.executedAction());
        if (event.score() < 0 || event.score() > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        String path = required(event.path(), "path");
        if (path.length() > maxPathLength) {
            path = path.substring(0, maxPathLength);
        }
        return new DecisionEvent(
                event.eventId(), event.occurredAt(),
                pseudonymizer.pseudonymize(bounded(event.clientKey(), "clientKey", 1_024)),
                required(event.method(), "method").substring(0, Math.min(16, event.method().length())),
                path,
                bounded(event.policyName(), "policyName", 128),
                bounded(event.policyRoute(), "policyRoute", 512),
                policyMode(event.policyMode()),
                bounded(event.tier(), "tier", 64),
                computed, executed, event.score(), bounded(event.reason(), "reason", 256),
                contributions(event.contributions()), event.dryRun(),
                optionalBounded(event.traceId(), "traceId", 64));
    }

    private static String normalizedAction(String action) {
        String normalized = required(action, "action").toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported action: " + normalized);
        }
        return normalized;
    }

    private static String policyMode(String value) {
        String normalized = required(value, "policyMode").toUpperCase(Locale.ROOT);
        if (!POLICY_MODES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported policyMode: " + normalized);
        }
        return normalized;
    }

    private static Map<String, Double> contributions(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > 64) {
            throw new IllegalArgumentException("contributions cannot contain more than 64 entries");
        }
        Map<String, Double> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String safeKey = bounded(key, "contribution key", 128);
            if (value == null || !Double.isFinite(value) || value < 0
                    || value > MAX_CONTRIBUTION_VALUE) {
                throw new IllegalArgumentException(
                        "contribution values must be finite and between 0 and "
                                + (long) MAX_CONTRIBUTION_VALUE);
            }
            sanitized.put(safeKey, value);
        });
        return Map.copyOf(sanitized);
    }

    private static String bounded(String value, String name, int maximum) {
        String required = required(value, name);
        if (required.length() > maximum) {
            throw new IllegalArgumentException(name + " cannot exceed " + maximum + " characters");
        }
        return required;
    }

    private static String optionalBounded(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return bounded(value, name, maximum);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " cannot contain control characters");
        }
        return value;
    }

    public record IngestResult(int inserted, int duplicates) {
    }
}
