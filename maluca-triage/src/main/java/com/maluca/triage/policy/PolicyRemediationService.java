package com.maluca.triage.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.contracts.policy.PolicyProposalRequest;
import com.maluca.contracts.policy.PolicyProposalView;
import com.maluca.contracts.policy.PolicyReconciliationRequest;
import com.maluca.contracts.policy.PolicyReconciliationView;
import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.report.TriageReportRepository;

@Service
public class PolicyRemediationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyRemediationService.class);

    private final IncidentRepository incidents;
    private final TriageReportRepository reports;
    private final PolicyProposalRepository proposals;
    private final PolicyPatchValidator validator;
    private final PolicyFileService files;
    private final PolicyApplyLock applyLock;
    private final AuditRepository audit;
    private final TransactionTemplate transaction;
    private final boolean applyEnabled;

    public PolicyRemediationService(IncidentRepository incidents, TriageReportRepository reports,
                                    PolicyProposalRepository proposals, PolicyPatchValidator validator,
                                    PolicyFileService files, PolicyApplyLock applyLock, AuditRepository audit,
                                    PlatformTransactionManager transactionManager,
                                    TriageProperties properties) {
        this.incidents = incidents;
        this.reports = reports;
        this.proposals = proposals;
        this.validator = validator;
        this.files = files;
        this.applyLock = applyLock;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.applyEnabled = properties.policy().applyEnabled();
    }

    @Transactional
    public PolicyProposalView propose(PolicyProposalRequest request, String actor) {
        if (request == null || request.incidentId() == null || request.patch() == null) {
            throw new IllegalArgumentException("incidentId and patch are required");
        }
        var incident = incidents.find(request.incidentId())
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        if (incident.status() != IncidentStatus.TRIAGED) {
            throw new IllegalStateException("incident must be TRIAGED before a proposal is stored");
        }
        var report = reports.findForIncident(incident.id())
                .filter(com.maluca.contracts.triage.TriageReportView::valid)
                .orElseThrow(() -> new IllegalStateException(
                        "proposal requires the current valid triage report"));
        List<String> errors = validator.validate(request.patch(), incident);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid policy patch: " + String.join("; ", errors));
        }
        String sha = files.sha256();
        // Resolve the patch against the actual policy document before it can be
        // stored for human review. This catches ineffective set operations and
        // final allow/deny CIDR overlaps at proposal time, not only during apply.
        files.targetSha256(request.patch(), sha);
        PolicyProposalView proposal = proposals.create(
                incident.id(), report.id(), report.createdAt(), actor, request.patch(), sha);
        audit.record(incident.id(), actor, "POLICY_PROPOSED",
                Map.of(
                        "proposalId", proposal.id(),
                        "reportId", report.id(),
                        "reportCreatedAt", report.createdAt().toString(),
                        "policySha256", sha));
        return proposal;
    }

    public PolicyProposalView apply(UUID incidentId, ApprovalRequest request, String authenticatedActor) {
        if (!applyEnabled) {
            throw new IllegalStateException("policy apply is disabled");
        }
        if (request == null || request.expectedPolicySha256() == null
                || request.expectedPolicySha256().isBlank()) {
            throw new IllegalArgumentException("expectedPolicySha256 is required");
        }
        if (request.proposalId() == null) {
            throw new IllegalArgumentException("proposalId is required");
        }
        if (request.expectedProposalSha256() == null
                || !request.expectedProposalSha256().matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException(
                    "expectedProposalSha256 must be 64 hexadecimal characters");
        }
        if (!request.expectedPolicySha256().matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("expectedPolicySha256 must be 64 hexadecimal characters");
        }
        if (request.expectedIncidentVersion() < 0) {
            throw new IllegalArgumentException("expectedIncidentVersion must be non-negative");
        }
        if (request.approvedBy() != null
                && request.approvedBy().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("approvedBy cannot contain control characters");
        }
        return applyLock.execute(() -> applyLocked(incidentId, request, authenticatedActor));
    }

    /**
     * Reconciles an indeterminate apply without accepting a caller-selected
     * outcome. The live file and proxy determine whether target or baseline is
     * authoritative; any third state remains fenced for manual investigation.
     */
    public PolicyReconciliationView reconcileIndeterminate(
            UUID incidentId, PolicyReconciliationRequest request, String authenticatedActor) {
        if (!applyEnabled) {
            throw new IllegalStateException("policy apply is disabled");
        }
        validateReconciliationRequest(request);
        return applyLock.execute(
                () -> reconcileIndeterminateLocked(incidentId, request, authenticatedActor));
    }

    private PolicyProposalView applyLocked(UUID incidentId, ApprovalRequest request,
                                           String authenticatedActor) {
        String actor = authenticatedActor;
        if (request.approvedBy() != null && !request.approvedBy().isBlank()) {
            actor = authenticatedActor + ":" + request.approvedBy().substring(
                    0, Math.min(100, request.approvedBy().length()));
        }
        var incident = incidents.find(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        if (incident.status() != IncidentStatus.TRIAGED) {
            throw new IllegalStateException("incident must be TRIAGED before approval");
        }
        if (incident.version() != request.expectedIncidentVersion()) {
            throw new IllegalStateException("incident changed since it was reviewed");
        }
        var proposal = proposals.findProposed(request.proposalId(), incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "reviewed proposal is not pending for this incident"));
        if (!proposal.proposalSha256().equalsIgnoreCase(request.expectedProposalSha256())
                || !proposals.patchDigestMatches(proposal.id())) {
            throw new IllegalStateException("approval proposal digest does not match the stored patch");
        }
        if (!proposal.policySha256().equalsIgnoreCase(request.expectedPolicySha256())) {
            throw new IllegalStateException("approval policy hash does not match the reviewed proposal");
        }
        List<String> validation = validator.validate(proposal.patch(), incident);
        if (!validation.isEmpty()) {
            throw new IllegalStateException("stored proposal no longer validates: " + String.join("; ", validation));
        }
        String targetPolicySha256 = files.targetSha256(proposal.patch(), proposal.policySha256());
        approveState(incident, proposal, actor, targetPolicySha256);

        return mutateAndFinalize(incidentId, proposal, actor);
    }

    private PolicyReconciliationView reconcileIndeterminateLocked(
            UUID incidentId, PolicyReconciliationRequest request, String actor) {
        var incident = incidents.find(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        if (incident.status() != IncidentStatus.APPLY_INDETERMINATE) {
            throw new IllegalStateException("incident must be APPLY_INDETERMINATE for reconciliation");
        }
        if (incident.version() != request.expectedIncidentVersion()) {
            throw new IllegalStateException("incident changed since reconciliation was reviewed");
        }
        PolicyProposalView proposal = proposals.find(request.proposalId())
                .filter(value -> value.incidentId().equals(incidentId))
                .filter(value -> "APPLY_INDETERMINATE".equals(value.status()))
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "indeterminate proposal is not pending for this incident"));
        verifyReconciliationBinding(proposal, request);

        String currentSha256 = files.sha256();
        if (currentSha256.equalsIgnoreCase(request.expectedTargetPolicySha256())) {
            files.verifyApplied(proposal.patch(), request.expectedTargetPolicySha256());
            return finalizeReconciliation(incident, proposal, request, actor, currentSha256,
                    PolicyReconciliationView.Outcome.TARGET_CONFIRMED);
        }
        if (currentSha256.equalsIgnoreCase(request.expectedBaselinePolicySha256())) {
            files.verifyCurrentPolicy(request.expectedBaselinePolicySha256());
            return finalizeReconciliation(incident, proposal, request, actor, currentSha256,
                    PolicyReconciliationView.Outcome.BASELINE_CONFIRMED);
        }

        transaction.executeWithoutResult(status -> audit.record(
                incidentId, actor, "POLICY_RECONCILIATION_REFUSED", Map.of(
                        "proposalId", proposal.id(),
                        "proposalSha256", proposal.proposalSha256(),
                        "baselinePolicySha256", proposal.policySha256(),
                        "targetPolicySha256", proposal.targetPolicySha256(),
                        "observedPolicySha256", currentSha256,
                        "reason", "active policy matches neither recorded digest")));
        throw new IllegalStateException(
                "active policy matches neither the recorded baseline nor target digest");
    }

    private PolicyReconciliationView finalizeReconciliation(
            com.maluca.contracts.incident.IncidentView incident,
            PolicyProposalView proposal,
            PolicyReconciliationRequest request,
            String actor,
            String observedSha256,
            PolicyReconciliationView.Outcome outcome) {
        return transaction.execute(status -> {
            IncidentStatus destination = outcome == PolicyReconciliationView.Outcome.TARGET_CONFIRMED
                    ? IncidentStatus.APPLIED : IncidentStatus.APPLY_FAILED;
            if (!incidents.transition(incident.id(), request.expectedIncidentVersion(),
                    IncidentStatus.APPLY_INDETERMINATE, destination)) {
                throw new IllegalStateException(
                        "incident changed concurrently during policy reconciliation");
            }
            boolean proposalUpdated;
            if (outcome == PolicyReconciliationView.Outcome.TARGET_CONFIRMED) {
                proposalUpdated = proposals.reconcileApplied(
                        proposal.id(), incident.id(), request.expectedProposalSha256(),
                        request.expectedBaselinePolicySha256(),
                        request.expectedTargetPolicySha256(), Instant.now());
            } else {
                proposalUpdated = proposals.reconcileBaseline(
                        proposal.id(), incident.id(), request.expectedProposalSha256(),
                        request.expectedBaselinePolicySha256(),
                        request.expectedTargetPolicySha256(),
                        "operator reconciliation confirmed the approved baseline is active");
            }
            if (!proposalUpdated) {
                throw new IllegalStateException(
                        "proposal changed concurrently during policy reconciliation");
            }
            audit.record(incident.id(), actor,
                    outcome == PolicyReconciliationView.Outcome.TARGET_CONFIRMED
                            ? "POLICY_RECONCILED_TARGET" : "POLICY_RECONCILED_BASELINE",
                    Map.of(
                            "proposalId", proposal.id(),
                            "proposalSha256", proposal.proposalSha256(),
                            "baselinePolicySha256", proposal.policySha256(),
                            "targetPolicySha256", proposal.targetPolicySha256(),
                            "observedPolicySha256", observedSha256,
                            "previousIncidentVersion", request.expectedIncidentVersion()));
            PolicyProposalView updated = proposals.find(proposal.id())
                    .orElseThrow(() -> new IllegalStateException(
                            "reconciled proposal disappeared"));
            return new PolicyReconciliationView(outcome, observedSha256, updated);
        });
    }

    private void verifyReconciliationBinding(
            PolicyProposalView proposal, PolicyReconciliationRequest request) {
        if (!proposal.proposalSha256().equalsIgnoreCase(request.expectedProposalSha256())
                || !proposal.policySha256().equalsIgnoreCase(
                request.expectedBaselinePolicySha256())
                || proposal.targetPolicySha256() == null
                || !proposal.targetPolicySha256().equalsIgnoreCase(
                request.expectedTargetPolicySha256())
                || !proposals.patchDigestMatches(proposal.id())) {
            throw new IllegalStateException(
                    "reconciliation fingerprints do not match the stored approved proposal");
        }
        if (proposal.policySha256().equalsIgnoreCase(proposal.targetPolicySha256())) {
            throw new IllegalStateException(
                    "indeterminate proposal has identical baseline and target digests");
        }
    }

    private static void validateReconciliationRequest(PolicyReconciliationRequest request) {
        if (request == null || request.proposalId() == null) {
            throw new IllegalArgumentException("proposalId is required");
        }
        validateDigest(request.expectedProposalSha256(), "expectedProposalSha256");
        validateDigest(request.expectedBaselinePolicySha256(),
                "expectedBaselinePolicySha256");
        validateDigest(request.expectedTargetPolicySha256(), "expectedTargetPolicySha256");
        if (request.expectedIncidentVersion() < 0) {
            throw new IllegalArgumentException("expectedIncidentVersion must be non-negative");
        }
    }

    private static void validateDigest(String digest, String name) {
        if (digest == null || !digest.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException(name + " must be 64 hexadecimal characters");
        }
    }

    protected void approveState(com.maluca.contracts.incident.IncidentView incident,
                                PolicyProposalView proposal, String actor,
                                String targetPolicySha256) {
        transaction.executeWithoutResult(status -> {
            if (!incidents.transition(incident.id(), incident.version(),
                    IncidentStatus.TRIAGED, IncidentStatus.APPROVED)) {
                throw new IllegalStateException("incident changed concurrently during approval");
            }
            Instant approvedAt = Instant.now();
            if (!proposals.approved(proposal.id(), approvedAt, targetPolicySha256)) {
                throw new IllegalStateException("proposal changed concurrently during approval");
            }
            audit.record(incident.id(), actor, "POLICY_APPROVED", Map.of(
                    "proposalId", proposal.id(),
                    "proposalSha256", proposal.proposalSha256(),
                    "targetPolicySha256", targetPolicySha256));
        });
    }

    private PolicyProposalView mutateAndFinalize(UUID incidentId, PolicyProposalView proposal, String actor) {
        PolicyFileService.ApplyResult result = null;
        try {
            result = files.apply(proposal.patch(), proposal.policySha256());
            return finalizeApplied(incidentId, proposal, actor, result);
        } catch (PolicyFileService.PolicyApplyIndeterminateException e) {
            recordIndeterminate(incidentId, proposal.id(), actor, e);
            throw e;
        } catch (RuntimeException e) {
            if (result == null) {
                recordFailed(incidentId, proposal.id(), actor, e);
                throw e;
            }
            try {
                files.rollback(result);
                recordFailed(incidentId, proposal.id(), actor, e);
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
                recordIndeterminate(incidentId, proposal.id(), actor, e);
            }
            throw e;
        }
    }

    /** Recovers crashes between durable approval, external mutation, and DB finalization. */
    @Scheduled(fixedDelayString = "${maluca.triage.policy.reconcile-interval:30s}")
    public void reconcileApproved() {
        if (!applyEnabled) {
            return;
        }
        for (PolicyProposalView candidate : proposals.findApproved(20)) {
            try {
                applyLock.execute(() -> {
                    reconcileApprovedLocked(candidate.id());
                    return null;
                });
            } catch (RuntimeException failure) {
                log.warn("policy_reconciliation_deferred proposalId={} reason={}",
                        candidate.id(), safeFailure(failure));
            }
        }
    }

    private void reconcileApprovedLocked(UUID proposalId) {
        PolicyProposalView proposal = proposals.find(proposalId)
                .filter(value -> "APPROVED".equals(value.status()))
                .orElse(null);
        if (proposal == null) {
            return;
        }
        var incident = incidents.find(proposal.incidentId())
                .orElseThrow(() -> new IllegalStateException("approved proposal has no incident"));
        if (incident.status() != IncidentStatus.APPROVED
                && incident.status() != IncidentStatus.APPLIED) {
            throw new IllegalStateException("approved proposal and incident status disagree");
        }
        if (!proposals.patchDigestMatches(proposal.id())) {
            recordIndeterminate(proposal.incidentId(), proposal.id(), "reconciler",
                    new IllegalStateException("stored proposal digest changed"));
            return;
        }
        if (proposal.targetPolicySha256() == null) {
            recordIndeterminate(proposal.incidentId(), proposal.id(), "reconciler",
                    new IllegalStateException(
                            "approved proposal predates target-digest reconciliation metadata"));
            return;
        }
        String currentSha = files.sha256();
        if (currentSha.equalsIgnoreCase(proposal.targetPolicySha256())) {
            files.verifyApplied(proposal.patch(), proposal.targetPolicySha256());
            finalizeApplied(proposal.incidentId(), proposal, "reconciler",
                    new PolicyFileService.ApplyResult(
                            proposal.policySha256(), proposal.targetPolicySha256(),
                            "reconciled-existing-target"));
            return;
        }
        if (currentSha.equalsIgnoreCase(proposal.policySha256())) {
            mutateAndFinalize(proposal.incidentId(), proposal, "reconciler");
            return;
        }
        recordIndeterminate(proposal.incidentId(), proposal.id(), "reconciler",
                new IllegalStateException(
                        "active policy matches neither the approved baseline nor target digest"));
    }

    protected PolicyProposalView finalizeApplied(UUID incidentId, PolicyProposalView proposal,
                                                 String actor, PolicyFileService.ApplyResult result) {
        return transaction.execute(status -> {
            Instant appliedAt = Instant.now();
            if (!proposals.applied(proposal.id(), appliedAt)) {
                throw new IllegalStateException("approved proposal state changed before finalization");
            }
            incidents.setStatus(incidentId, IncidentStatus.APPLIED);
            audit.record(incidentId, actor, "POLICY_APPLIED", Map.of(
                    "proposalId", proposal.id(),
                    "previousSha256", result.previousSha256(),
                    "appliedSha256", result.appliedSha256(),
                    "backup", result.backupPath()));
            return proposals.find(proposal.id()).orElseThrow();
        });
    }

    protected void recordFailed(UUID incidentId, UUID proposalId, String actor, RuntimeException error) {
        transaction.executeWithoutResult(status -> {
            String failure = safeFailure(error);
            proposals.failed(proposalId, failure);
            incidents.setStatus(incidentId, IncidentStatus.APPLY_FAILED);
            audit.record(incidentId, actor, "POLICY_APPLY_FAILED",
                    Map.of("proposalId", proposalId, "failure", failure));
        });
    }

    protected void recordIndeterminate(UUID incidentId, UUID proposalId, String actor,
                                       RuntimeException error) {
        transaction.executeWithoutResult(status -> {
            String failure = safeFailure(error);
            proposals.indeterminate(proposalId, failure);
            incidents.setStatus(incidentId, IncidentStatus.APPLY_INDETERMINATE);
            audit.record(incidentId, actor, "POLICY_APPLY_INDETERMINATE",
                    Map.of("proposalId", proposalId, "failure", failure));
        });
    }

    private static String safeFailure(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.substring(0, Math.min(2_000, message.length()));
    }
}
