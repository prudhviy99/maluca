package com.maluca.triage.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.incident.IncidentDismissRequest;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.policy.AuditRepository;

class IncidentLifecycleServiceTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final IncidentLifecycleService lifecycle = new IncidentLifecycleService(incidents, audit);

    @Test
    void operatorCanClosePoisonIncidentByExactVersionWithAudit() {
        IncidentView failed = incident(IncidentStatus.TRIAGE_FAILED, 7, null);
        IncidentView dismissed = incident(
                IncidentStatus.DISMISSED, 8, Instant.parse("2026-08-12T13:00:00Z"));
        when(incidents.find(failed.id())).thenReturn(Optional.of(failed), Optional.of(dismissed));
        when(incidents.dismissTriageFailure(eq(failed.id()), eq(7L), any(Instant.class)))
                .thenReturn(true);

        IncidentView result = lifecycle.dismissTriageFailure(
                failed.id(), new IncidentDismissRequest(7, "dependency fixed; superseded"), "operator");

        assertThat(result.status()).isEqualTo(IncidentStatus.DISMISSED);
        verify(audit).record(eq(failed.id()), eq("operator"),
                eq("INCIDENT_TRIAGE_FAILURE_DISMISSED"), any());
    }

    @Test
    void dismissalRejectsStaleVersionBeforeMutation() {
        IncidentView failed = incident(IncidentStatus.TRIAGE_FAILED, 7, null);
        when(incidents.find(failed.id())).thenReturn(Optional.of(failed));

        assertThatThrownBy(() -> lifecycle.dismissTriageFailure(
                failed.id(), new IncidentDismissRequest(6, "reviewed"), "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed since it was reviewed");

        verify(incidents, never()).dismissTriageFailure(any(), eq(6L), any());
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void dismissalRejectsControlCharactersAndNonTerminalIncident() {
        IncidentView open = incident(IncidentStatus.OPEN, 7, null);
        when(incidents.find(open.id())).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> lifecycle.dismissTriageFailure(
                open.id(), new IncidentDismissRequest(7, "not terminal"), "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRIAGE_FAILED");
        assertThatThrownBy(() -> lifecycle.dismissTriageFailure(
                open.id(), new IncidentDismissRequest(7, "bad\nreason"), "operator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    private static IncidentView incident(IncidentStatus status, long version, Instant closedAt) {
        IncidentView base = TriageTestFixtures.incident();
        return new IncidentView(base.id(), base.openedAt(), closedAt,
                base.policyName(), base.policyRoute(), base.trigger(), status, base.stats(),
                version, null, 3, null, "model output stayed invalid");
    }
}
