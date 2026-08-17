package com.maluca.triage.incident;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maluca.contracts.incident.IncidentDismissRequest;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.policy.AuditRepository;

@Service
public class IncidentLifecycleService {

    static final int MAX_DISMISS_REASON_LENGTH = 500;

    private final IncidentRepository incidents;
    private final AuditRepository audit;

    public IncidentLifecycleService(IncidentRepository incidents, AuditRepository audit) {
        this.incidents = incidents;
        this.audit = audit;
    }

    /**
     * Acknowledges a poison incident without making it eligible for another
     * automated retry. Closing it releases the one-active-incident constraint.
     */
    @Transactional
    public IncidentView dismissTriageFailure(
            UUID incidentId, IncidentDismissRequest request, String actor) {
        if (incidentId == null) {
            throw new IllegalArgumentException("incidentId is required");
        }
        if (request == null || request.expectedIncidentVersion() < 0) {
            throw new IllegalArgumentException("expectedIncidentVersion must be non-negative");
        }
        String reason = normalizeReason(request.reason());
        var incident = incidents.find(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        if (incident.status() != IncidentStatus.TRIAGE_FAILED || incident.closedAt() != null) {
            throw new IllegalStateException("only an open TRIAGE_FAILED incident can be dismissed");
        }
        if (incident.version() != request.expectedIncidentVersion()) {
            throw new IllegalStateException("incident changed since it was reviewed");
        }
        Instant closedAt = Instant.now();
        if (!incidents.dismissTriageFailure(
                incidentId, request.expectedIncidentVersion(), closedAt)) {
            throw new IllegalStateException("incident changed concurrently during dismissal");
        }
        audit.record(incidentId, actor, "INCIDENT_TRIAGE_FAILURE_DISMISSED", Map.of(
                "reason", reason,
                "previousVersion", request.expectedIncidentVersion(),
                "triageAttempts", incident.triageAttempts(),
                "triageFailure", safeNullable(incident.triageFailure())));
        return incidents.find(incidentId)
                .orElseThrow(() -> new IllegalStateException("dismissed incident disappeared"));
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            throw new IllegalArgumentException("reason is required");
        }
        String reason = value.trim();
        if (reason.isEmpty() || reason.length() > MAX_DISMISS_REASON_LENGTH
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "reason must contain 1 to " + MAX_DISMISS_REASON_LENGTH
                            + " characters without control characters");
        }
        return reason;
    }

    private static String safeNullable(String value) {
        return value == null ? "" : value.substring(0, Math.min(2_000, value.length()));
    }
}
