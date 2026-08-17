package com.maluca.triage.agent;

import java.util.Optional;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.incident.IncidentClaim;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.incident.TriageRetryPolicy;
import com.maluca.triage.policy.PolicyProposalRepository;
import com.maluca.triage.policy.PolicyFileService;
import com.maluca.triage.policy.AuditRepository;
import com.maluca.triage.report.TriageReportRepository;

/** Persists a report only after fencing stale workers with the current lease ID. */
@Service
public class IncidentTriageCompletion {

    private final IncidentRepository incidents;
    private final TriageReportRepository reports;
    private final PolicyProposalRepository proposals;
    private final PolicyFileService policyFiles;
    private final AuditRepository audit;
    private final TriageProperties properties;

    public IncidentTriageCompletion(
            IncidentRepository incidents,
            TriageReportRepository reports,
            PolicyProposalRepository proposals,
            PolicyFileService policyFiles,
            AuditRepository audit,
            TriageProperties properties) {
        this.incidents = incidents;
        this.reports = reports;
        this.proposals = proposals;
        this.policyFiles = policyFiles;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public boolean complete(IncidentClaim claim, TriageAgent.AgentResult output) {
        if (!output.valid()) {
            throw new IllegalArgumentException("invalid model output must use completeFallback");
        }
        if (!incidents.lockClaim(claim.incident().id(), claim.leaseId())) {
            return false;
        }
        saveReportAndProposal(claim, output);
        if (!incidents.completeClaim(claim.incident().id(), claim.leaseId())) {
            throw new IllegalStateException("triage lease changed while committing its report");
        }
        return true;
    }

    /** Stores the rejected output while returning the incident to retry/manual review. */
    @Transactional
    public Optional<IncidentRepository.TriageFailure> completeFallback(
            IncidentClaim claim, TriageAgent.AgentResult output,
            TriageRetryPolicy.FailurePlan plan, String failure) {
        if (output.valid()) {
            throw new IllegalArgumentException("valid model output must use complete");
        }
        if (!incidents.lockClaim(claim.incident().id(), claim.leaseId())) {
            return Optional.empty();
        }
        saveDiagnosticReport(claim, output);
        return Optional.of(incidents.recordTriageFailure(claim, plan, failure)
                .orElseThrow(() -> new IllegalStateException(
                        "triage lease changed while committing fallback output")));
    }

    private void saveReportAndProposal(IncidentClaim claim, TriageAgent.AgentResult output) {
        var report = reports.save(
                claim.incident().id(), properties.agent().model(), properties.agent().promptVersion(),
                output.result(), output.valid(), output.validationErrors(), output.rawResponse(),
                output.retrievedChunks());
        proposals.quarantineStaleProposed(claim.incident().id(), report.id(), report.createdAt());
        if (output.result().proposedPatch() != null) {
            String policySha = policyFiles.sha256();
            policyFiles.targetSha256(output.result().proposedPatch(), policySha);
            var proposal = proposals.create(claim.incident().id(), report.id(), report.createdAt(),
                    "triage-agent", output.result().proposedPatch(), policySha);
            audit.record(claim.incident().id(), "triage-agent", "POLICY_PROPOSED", Map.of(
                    "proposalId", proposal.id(),
                    "reportId", report.id(),
                    "reportCreatedAt", report.createdAt().toString(),
                    "policySha256", policySha));
        }
    }

    private void saveDiagnosticReport(IncidentClaim claim, TriageAgent.AgentResult output) {
        var report = reports.save(
                claim.incident().id(), properties.agent().model(), properties.agent().promptVersion(),
                output.result(), output.valid(), output.validationErrors(), output.rawResponse(),
                output.retrievedChunks());
        proposals.quarantineStaleProposed(claim.incident().id(), report.id(), report.createdAt());
    }
}
