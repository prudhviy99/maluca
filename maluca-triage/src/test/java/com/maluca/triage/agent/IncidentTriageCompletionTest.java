package com.maluca.triage.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.triage.TriageReportView;
import com.maluca.contracts.triage.TriageResult;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.incident.IncidentClaim;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.incident.TriageRetryPolicy;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.triage.policy.PolicyProposalRepository;
import com.maluca.triage.policy.PolicyFileService;
import com.maluca.triage.policy.AuditRepository;
import com.maluca.triage.report.TriageReportRepository;

@ExtendWith(MockitoExtension.class)
class IncidentTriageCompletionTest {

    @Mock
    IncidentRepository incidents;
    @Mock
    TriageReportRepository reports;
    @Mock
    PolicyProposalRepository proposals;
    @Mock
    PolicyFileService policyFiles;
    @Mock
    AuditRepository audit;

    private IncidentTriageCompletion completion;
    private IncidentClaim claim;
    private TriageAgent.AgentResult output;

    @BeforeEach
    void setUp() {
        completion = new IncidentTriageCompletion(
                incidents, reports, proposals, policyFiles,
                audit,
                TriageTestFixtures.properties(Path.of("policies.yml")));
        claim = new IncidentClaim(
                TriageTestFixtures.incident(), UUID.fromString("00000000-0000-0000-0000-000000000456"),
                1, Instant.parse("2026-08-13T12:00:00Z"));
        output = new TriageAgent.AgentResult(
                new TriageResult(Classification.UNKNOWN, Confidence.LOW,
                        "Insufficient grounded evidence.", List.of(), List.of(), null),
                true, List.of(), "{}", List.of());
    }

    @Test
    void staleLeaseCannotWriteOrReplaceAReport() {
        when(incidents.lockClaim(claim.incident().id(), claim.leaseId())).thenReturn(false);

        assertThat(completion.complete(claim, output)).isFalse();

        verifyNoInteractions(reports, proposals, audit);
        verify(incidents, never()).completeClaim(any(), any());
    }

    @Test
    void ownedLeaseCommitsReportAndStatusTogether() {
        TriageReportView report = mock(TriageReportView.class);
        UUID reportId = UUID.fromString("00000000-0000-0000-0000-000000000789");
        when(report.id()).thenReturn(reportId);
        when(report.createdAt()).thenReturn(Instant.parse("2026-08-13T12:00:01Z"));
        when(incidents.lockClaim(claim.incident().id(), claim.leaseId())).thenReturn(true);
        when(reports.save(eq(claim.incident().id()), anyString(), anyString(), any(),
                anyBoolean(), anyList(), anyString(), anyList())).thenReturn(report);
        when(incidents.completeClaim(claim.incident().id(), claim.leaseId())).thenReturn(true);

        assertThat(completion.complete(claim, output)).isTrue();

        verify(proposals).quarantineStaleProposed(
                claim.incident().id(), reportId, report.createdAt());
        verify(incidents).completeClaim(claim.incident().id(), claim.leaseId());
    }

    @Test
    void invalidFallbackIsStoredButReturnedToBackoffInsteadOfMarkedTriaged() {
        TriageReportView report = mock(TriageReportView.class);
        UUID reportId = UUID.fromString("00000000-0000-0000-0000-000000000789");
        var fallback = new TriageAgent.AgentResult(output.result(), false,
                List.of("citation missing"), "{}", List.of());
        var plan = new TriageRetryPolicy.FailurePlan(
                IncidentStatus.OPEN, Instant.parse("2026-08-13T12:00:30Z"));
        var failure = new IncidentRepository.TriageFailure(
                claim.incident().id(), IncidentStatus.OPEN, 1, plan.nextAttemptAt());
        when(report.id()).thenReturn(reportId);
        when(report.createdAt()).thenReturn(Instant.parse("2026-08-13T12:00:01Z"));
        when(incidents.lockClaim(claim.incident().id(), claim.leaseId())).thenReturn(true);
        when(reports.save(eq(claim.incident().id()), anyString(), anyString(), any(),
                anyBoolean(), anyList(), anyString(), anyList())).thenReturn(report);
        when(incidents.recordTriageFailure(claim, plan, "citation missing"))
                .thenReturn(java.util.Optional.of(failure));

        assertThat(completion.completeFallback(claim, fallback, plan, "citation missing"))
                .contains(failure);

        verify(proposals).quarantineStaleProposed(
                claim.incident().id(), reportId, report.createdAt());
        verify(proposals, never()).create(any(), any(), any(), anyString(), any(), anyString());
        verify(incidents, never()).completeClaim(any(), any());
    }

    @Test
    void validatedModelPatchIsPersistedWithExactReportGenerationAndPolicyBaseline() {
        UUID reportId = UUID.fromString("00000000-0000-0000-0000-000000000789");
        Instant reportCreatedAt = Instant.parse("2026-08-13T12:00:01Z");
        String policySha = "a".repeat(64);
        PolicyPatch patch = new PolicyPatch(
                "api", "/api/**", "DRY_RUN", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null,
                "route-scoped safety proposal");
        TriageReportView report = mock(TriageReportView.class);
        var patchedOutput = new TriageAgent.AgentResult(
                new TriageResult(Classification.BURST_FLOOD, Confidence.HIGH,
                        "A burst flood is concentrated on the API route.",
                        List.of(), List.of(), patch),
                true, List.of(), "{}", List.of());
        when(report.id()).thenReturn(reportId);
        when(report.createdAt()).thenReturn(reportCreatedAt);
        when(incidents.lockClaim(claim.incident().id(), claim.leaseId())).thenReturn(true);
        when(reports.save(eq(claim.incident().id()), anyString(), anyString(), any(),
                anyBoolean(), anyList(), anyString(), anyList())).thenReturn(report);
        when(policyFiles.sha256()).thenReturn(policySha);
        when(policyFiles.targetSha256(patch, policySha)).thenReturn("b".repeat(64));
        var proposal = mock(com.maluca.contracts.policy.PolicyProposalView.class);
        when(proposal.id()).thenReturn(
                UUID.fromString("19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1"));
        when(proposals.create(claim.incident().id(), reportId, reportCreatedAt,
                "triage-agent", patch, policySha)).thenReturn(proposal);
        when(incidents.completeClaim(claim.incident().id(), claim.leaseId())).thenReturn(true);

        assertThat(completion.complete(claim, patchedOutput)).isTrue();

        verify(proposals).quarantineStaleProposed(
                claim.incident().id(), reportId, reportCreatedAt);
        verify(policyFiles).targetSha256(patch, policySha);
        verify(proposals).create(claim.incident().id(), reportId, reportCreatedAt,
                "triage-agent", patch, policySha);
        verify(audit).record(eq(claim.incident().id()), eq("triage-agent"),
                eq("POLICY_PROPOSED"), any());
    }
}
