package com.maluca.triage.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.policy.PolicyProposalView;
import com.maluca.contracts.policy.PolicyReconciliationRequest;
import com.maluca.contracts.policy.PolicyReconciliationView;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.report.TriageReportRepository;

class PolicyRemediationServiceTest {

    private static final UUID PROPOSAL_A = UUID.fromString("19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1");
    private static final UUID PROPOSAL_B = UUID.fromString("297fe3dc-31e8-477b-ac70-f016a1cde5a4");
    private static final String PROPOSAL_SHA = "b".repeat(64);
    private static final String POLICY_SHA = "a".repeat(64);
    private static final String TARGET_SHA = "c".repeat(64);

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final TriageReportRepository reports = mock(TriageReportRepository.class);
    private final PolicyProposalRepository proposals = mock(PolicyProposalRepository.class);
    private final PolicyPatchValidator validator = mock(PolicyPatchValidator.class);
    private final PolicyFileService files = mock(PolicyFileService.class);
    private final PolicyApplyLock applyLock = mock(PolicyApplyLock.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private PolicyRemediationService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(applyLock.execute(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
        service = new PolicyRemediationService(
                incidents, reports, proposals, validator, files, applyLock, audit,
                transactionManager, TriageTestFixtures.properties(Path.of("policies.yml")));
        var report = mock(com.maluca.contracts.triage.TriageReportView.class);
        lenient().when(report.valid()).thenReturn(true);
        lenient().when(report.id()).thenReturn(
                UUID.fromString("00000000-0000-0000-0000-000000000789"));
        lenient().when(report.createdAt()).thenReturn(
                Instant.parse("2026-08-12T12:00:30Z"));
        lenient().when(reports.findForIncident(any())).thenReturn(java.util.Optional.of(report));
    }

    @Test
    void approvalIsBoundToTheExactReviewedProposalAndPatchDigest() {
        var incident = TriageTestFixtures.incident();
        var proposal = proposal(PROPOSAL_A, "PROPOSED");
        var applied = proposal(PROPOSAL_A, "APPLIED");
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.findProposed(PROPOSAL_A, incident.id()))
                .thenReturn(java.util.Optional.of(proposal));
        when(proposals.patchDigestMatches(PROPOSAL_A)).thenReturn(true);
        when(validator.validate(proposal.patch(), incident)).thenReturn(List.of());
        when(files.targetSha256(proposal.patch(), POLICY_SHA)).thenReturn(TARGET_SHA);
        when(incidents.transition(incident.id(), incident.version(), incident.status(),
                com.maluca.contracts.incident.IncidentStatus.APPROVED)).thenReturn(true);
        when(proposals.approved(any(), any(), anyString())).thenReturn(true);
        var result = new PolicyFileService.ApplyResult(POLICY_SHA, TARGET_SHA, "/tmp/policy.bak");
        when(files.apply(proposal.patch(), POLICY_SHA)).thenReturn(result);
        when(proposals.applied(any(), any())).thenReturn(true);
        when(proposals.find(PROPOSAL_A)).thenReturn(java.util.Optional.of(applied));

        PolicyProposalView response = service.apply(incident.id(),
                new ApprovalRequest(PROPOSAL_A, PROPOSAL_SHA, POLICY_SHA,
                        incident.version(), "on-call"), "operator");

        assertThat(response.id()).isEqualTo(PROPOSAL_A);
        verify(proposals).findProposed(PROPOSAL_A, incident.id());
        verify(files).apply(proposal.patch(), POLICY_SHA);
        verify(proposals, never()).findProposed(PROPOSAL_B, incident.id());
    }

    @Test
    void unknownOrWrongIncidentProposalFailsBeforeAnyFileMutation() {
        var incident = TriageTestFixtures.incident();
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.findProposed(PROPOSAL_B, incident.id()))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.apply(incident.id(),
                new ApprovalRequest(PROPOSAL_B, PROPOSAL_SHA, POLICY_SHA,
                        incident.version(), "on-call"), "operator"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("not pending for this incident");

        verify(files, never()).targetSha256(any(), anyString());
        verify(files, never()).apply(any(), anyString());
    }

    @Test
    void proposalResolvesPatchAgainstTheLivePolicyBeforePersistence() {
        var incident = TriageTestFixtures.incident();
        PolicyPatch patch = proposal(PROPOSAL_A, "PROPOSED").patch();
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(validator.validate(patch, incident)).thenReturn(List.of());
        when(files.sha256()).thenReturn(POLICY_SHA);
        when(files.targetSha256(patch, POLICY_SHA))
                .thenThrow(new IllegalArgumentException("allowlist and denylist overlap"));

        assertThatThrownBy(() -> service.propose(
                new com.maluca.contracts.policy.PolicyProposalRequest(incident.id(), patch), "agent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist and denylist overlap");

        verify(proposals, never()).create(any(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    void failedDatabaseFinalizationCompensatesTheAlreadyAppliedPolicy() {
        var incident = TriageTestFixtures.incident();
        var proposal = proposal(PROPOSAL_A, "PROPOSED");
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.findProposed(PROPOSAL_A, incident.id()))
                .thenReturn(java.util.Optional.of(proposal));
        when(proposals.patchDigestMatches(PROPOSAL_A)).thenReturn(true);
        when(validator.validate(any(), any())).thenReturn(List.of());
        when(files.targetSha256(any(), anyString())).thenReturn(TARGET_SHA);
        when(incidents.transition(any(), anyLong(), any(), any())).thenReturn(true);
        when(proposals.approved(any(), any(), anyString())).thenReturn(true);
        var result = new PolicyFileService.ApplyResult(POLICY_SHA, TARGET_SHA, "/tmp/policy.bak");
        when(files.apply(any(), anyString())).thenReturn(result);
        when(proposals.applied(any(), any())).thenReturn(false);
        when(proposals.failed(any(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.apply(incident.id(),
                new ApprovalRequest(PROPOSAL_A, PROPOSAL_SHA, POLICY_SHA,
                        incident.version(), "on-call"), "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state changed before finalization");

        verify(files).rollback(result);
        verify(proposals).failed(PROPOSAL_A,
                "approved proposal state changed before finalization");
        verify(incidents).setStatus(incident.id(),
                com.maluca.contracts.incident.IncidentStatus.APPLY_FAILED);
    }

    @Test
    void indeterminateTargetIsFinalizedOnlyAfterLiveDigestAndPolicyVerification() {
        var incident = incidentWithStatus(
                com.maluca.contracts.incident.IncidentStatus.APPLY_INDETERMINATE, 9);
        var proposal = proposal(PROPOSAL_A, "APPLY_INDETERMINATE");
        var applied = proposal(PROPOSAL_A, "APPLIED");
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.find(PROPOSAL_A)).thenReturn(
                java.util.Optional.of(proposal), java.util.Optional.of(applied));
        when(proposals.patchDigestMatches(PROPOSAL_A)).thenReturn(true);
        when(files.sha256()).thenReturn(TARGET_SHA);
        when(incidents.transition(incident.id(), incident.version(),
                com.maluca.contracts.incident.IncidentStatus.APPLY_INDETERMINATE,
                com.maluca.contracts.incident.IncidentStatus.APPLIED)).thenReturn(true);
        when(proposals.reconcileApplied(eq(PROPOSAL_A), eq(incident.id()), eq(PROPOSAL_SHA),
                eq(POLICY_SHA), eq(TARGET_SHA), any())).thenReturn(true);

        PolicyReconciliationView result = service.reconcileIndeterminate(
                incident.id(), reconciliation(incident.version()), "operator");

        assertThat(result.outcome()).isEqualTo(PolicyReconciliationView.Outcome.TARGET_CONFIRMED);
        assertThat(result.observedPolicySha256()).isEqualTo(TARGET_SHA);
        verify(files).verifyApplied(proposal.patch(), TARGET_SHA);
        verify(audit).record(eq(incident.id()), eq("operator"),
                eq("POLICY_RECONCILED_TARGET"), any());
    }

    @Test
    void indeterminateBaselineBecomesFailedWithoutReapplying() {
        var incident = incidentWithStatus(
                com.maluca.contracts.incident.IncidentStatus.APPLY_INDETERMINATE, 9);
        var proposal = proposal(PROPOSAL_A, "APPLY_INDETERMINATE");
        var failed = proposal(PROPOSAL_A, "APPLY_FAILED");
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.find(PROPOSAL_A)).thenReturn(
                java.util.Optional.of(proposal), java.util.Optional.of(failed));
        when(proposals.patchDigestMatches(PROPOSAL_A)).thenReturn(true);
        when(files.sha256()).thenReturn(POLICY_SHA);
        when(incidents.transition(incident.id(), incident.version(),
                com.maluca.contracts.incident.IncidentStatus.APPLY_INDETERMINATE,
                com.maluca.contracts.incident.IncidentStatus.APPLY_FAILED)).thenReturn(true);
        when(proposals.reconcileBaseline(PROPOSAL_A, incident.id(), PROPOSAL_SHA,
                POLICY_SHA, TARGET_SHA, "operator reconciliation confirmed the approved baseline is active"))
                .thenReturn(true);

        PolicyReconciliationView result = service.reconcileIndeterminate(
                incident.id(), reconciliation(incident.version()), "operator");

        assertThat(result.outcome()).isEqualTo(PolicyReconciliationView.Outcome.BASELINE_CONFIRMED);
        verify(files).verifyCurrentPolicy(POLICY_SHA);
        verify(files, never()).apply(any(), anyString());
    }

    @Test
    void indeterminateThirdDigestStaysFencedAndIsAudited() {
        var incident = incidentWithStatus(
                com.maluca.contracts.incident.IncidentStatus.APPLY_INDETERMINATE, 9);
        var proposal = proposal(PROPOSAL_A, "APPLY_INDETERMINATE");
        String unknownSha = "d".repeat(64);
        when(incidents.find(incident.id())).thenReturn(java.util.Optional.of(incident));
        when(proposals.find(PROPOSAL_A)).thenReturn(java.util.Optional.of(proposal));
        when(proposals.patchDigestMatches(PROPOSAL_A)).thenReturn(true);
        when(files.sha256()).thenReturn(unknownSha);

        assertThatThrownBy(() -> service.reconcileIndeterminate(
                incident.id(), reconciliation(incident.version()), "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither the recorded baseline nor target");

        verify(incidents, never()).transition(any(), anyLong(), any(), any());
        verify(audit).record(eq(incident.id()), eq("operator"),
                eq("POLICY_RECONCILIATION_REFUSED"), any());
    }

    private static PolicyReconciliationRequest reconciliation(long version) {
        return new PolicyReconciliationRequest(
                PROPOSAL_A, PROPOSAL_SHA, POLICY_SHA, TARGET_SHA, version);
    }

    private static com.maluca.contracts.incident.IncidentView incidentWithStatus(
            com.maluca.contracts.incident.IncidentStatus status, long version) {
        var base = TriageTestFixtures.incident();
        return new com.maluca.contracts.incident.IncidentView(
                base.id(), base.openedAt(), base.closedAt(), base.policyName(),
                base.policyRoute(), base.trigger(), status, base.stats(), version,
                base.triageClaimedAt(), base.triageAttempts(), base.triageNextAttemptAt(),
                base.triageFailure());
    }

    private static PolicyProposalView proposal(UUID id, String status) {
        PolicyPatch patch = new PolicyPatch("api", "/api/**", "DRY_RUN", null,
                null, null, List.of(), List.of(), List.of(), List.of(), null,
                "reviewed exact proposal");
        return new PolicyProposalView(id, TriageTestFixtures.incident().id(),
                UUID.fromString("00000000-0000-0000-0000-000000000789"),
                Instant.parse("2026-08-12T12:00:30Z"),
                Instant.parse("2026-08-12T12:01:00Z"), "agent", patch,
                PROPOSAL_SHA, POLICY_SHA,
                "PROPOSED".equals(status) ? null : TARGET_SHA,
                status, null, null, null);
    }
}
