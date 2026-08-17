package com.maluca.triage.policy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.policy.PolicyProposalView;

@Repository
public class PolicyProposalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PolicyProposalRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public PolicyProposalView create(UUID incidentId, UUID reportId, Instant reportCreatedAt,
                                     String actor, PolicyPatch patch, String policySha256) {
        if (reportId == null || reportCreatedAt == null) {
            throw new IllegalArgumentException("proposal requires exact report-generation provenance");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String serializedPatch = write(patch);
        int inserted = jdbc.update("""
                INSERT INTO policy_proposals
                    (id, incident_id, report_id, report_created_at, created_at, created_by, patch,
                     proposal_sha256, policy_sha256, status)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                    encode(digest(convert_to(CAST(? AS jsonb)::text, 'UTF8'), 'sha256'), 'hex'),
                    ?, 'PROPOSED')
                ON CONFLICT (report_id, report_created_at, proposal_sha256, created_by)
                    WHERE report_id IS NOT NULL AND report_created_at IS NOT NULL
                DO NOTHING
                """, id, incidentId, reportId, Timestamp.from(reportCreatedAt),
                Timestamp.from(now), actor,
                serializedPatch, serializedPatch, policySha256);
        if (inserted == 1) {
            return find(id).orElseThrow();
        }
        return findExact(reportId, reportCreatedAt, actor, serializedPatch)
                .orElseThrow(() -> new IllegalStateException(
                        "proposal deduplication conflict could not be resolved"));
    }

    public Optional<PolicyProposalView> find(UUID id) {
        return jdbc.query("SELECT * FROM policy_proposals WHERE id=?", rowMapper(), id).stream().findFirst();
    }

    public Optional<PolicyProposalView> findProposed(UUID id, UUID incidentId) {
        return jdbc.query("""
                SELECT proposal.* FROM policy_proposals AS proposal
                  JOIN triage_reports AS report
                    ON report.id = proposal.report_id
                   AND report.incident_id = proposal.incident_id
                   AND report.created_at = proposal.report_created_at
                   AND report.valid = true
                 WHERE proposal.id=? AND proposal.incident_id=?
                   AND proposal.status='PROPOSED'
                """, rowMapper(), id, incidentId).stream().findFirst();
    }

    public List<PolicyProposalView> findForIncident(UUID incidentId, int limit) {
        return jdbc.query("""
                SELECT * FROM policy_proposals WHERE incident_id=?
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, rowMapper(), incidentId, limit);
    }

    public List<PolicyProposalView> findApproved(int limit) {
        return jdbc.query("""
                SELECT * FROM policy_proposals WHERE status='APPROVED'
                 ORDER BY approved_at, id LIMIT ?
                """, rowMapper(), limit);
    }

    public boolean patchDigestMatches(UUID id) {
        Boolean matches = jdbc.queryForObject("""
                SELECT proposal_sha256 = encode(
                    digest(convert_to(patch::text, 'UTF8'), 'sha256'), 'hex')
                  FROM policy_proposals WHERE id=?
                """, Boolean.class, id);
        return Boolean.TRUE.equals(matches);
    }

    public int quarantineStaleProposed(
            UUID incidentId, UUID currentReportId, Instant currentReportCreatedAt) {
        return jdbc.update("""
                UPDATE policy_proposals
                   SET status='REJECTED_STALE_PROVENANCE',
                       failure='proposal is not bound to the current report generation'
                 WHERE incident_id=? AND status='PROPOSED'
                   AND (report_id IS DISTINCT FROM ?
                        OR report_created_at IS DISTINCT FROM ?)
                """, incidentId, currentReportId, Timestamp.from(currentReportCreatedAt));
    }

    public boolean approved(UUID id, Instant when, String targetPolicySha256) {
        return jdbc.update("""
                UPDATE policy_proposals
                   SET status='APPROVED', approved_at=?, target_policy_sha256=?
                 WHERE id=? AND status='PROPOSED'
                """, Timestamp.from(when), targetPolicySha256, id) == 1;
    }

    public boolean applied(UUID id, Instant when) {
        return jdbc.update("""
                UPDATE policy_proposals SET status='APPLIED', applied_at=?, failure=NULL
                 WHERE id=? AND status='APPROVED'
                """, Timestamp.from(when), id) == 1;
    }

    public boolean failed(UUID id, String failure) {
        return jdbc.update("""
                UPDATE policy_proposals SET status='APPLY_FAILED', failure=?
                 WHERE id=? AND status IN ('PROPOSED','APPROVED')
                """, failure.substring(0, Math.min(4_000, failure.length())), id) == 1;
    }

    public boolean indeterminate(UUID id, String failure) {
        return jdbc.update("""
                UPDATE policy_proposals SET status='APPLY_INDETERMINATE', failure=?
                 WHERE id=? AND status='APPROVED'
                """, failure.substring(0, Math.min(4_000, failure.length())), id) == 1;
    }

    /** Finalizes an indeterminate proposal only if every reviewed digest still matches. */
    public boolean reconcileApplied(UUID id, UUID incidentId, String proposalSha256,
                                    String baselinePolicySha256, String targetPolicySha256,
                                    Instant appliedAt) {
        return jdbc.update("""
                UPDATE policy_proposals
                   SET status='APPLIED', applied_at=?, failure=NULL
                 WHERE id=? AND incident_id=? AND status='APPLY_INDETERMINATE'
                   AND lower(proposal_sha256)=lower(?)
                   AND lower(policy_sha256)=lower(?)
                   AND lower(target_policy_sha256)=lower(?)
                   AND proposal_sha256 = encode(
                       digest(convert_to(patch::text, 'UTF8'), 'sha256'), 'hex')
                """, Timestamp.from(appliedAt), id, incidentId, proposalSha256,
                baselinePolicySha256, targetPolicySha256) == 1;
    }

    /** Records a verified baseline outcome under the same immutable CAS binding. */
    public boolean reconcileBaseline(UUID id, UUID incidentId, String proposalSha256,
                                     String baselinePolicySha256, String targetPolicySha256,
                                     String failure) {
        String safeFailure = failure.substring(0, Math.min(4_000, failure.length()));
        return jdbc.update("""
                UPDATE policy_proposals
                   SET status='APPLY_FAILED', failure=?
                 WHERE id=? AND incident_id=? AND status='APPLY_INDETERMINATE'
                   AND lower(proposal_sha256)=lower(?)
                   AND lower(policy_sha256)=lower(?)
                   AND lower(target_policy_sha256)=lower(?)
                   AND proposal_sha256 = encode(
                       digest(convert_to(patch::text, 'UTF8'), 'sha256'), 'hex')
                """, safeFailure, id, incidentId, proposalSha256,
                baselinePolicySha256, targetPolicySha256) == 1;
    }

    private RowMapper<PolicyProposalView> rowMapper() {
        return (rs, rowNum) -> new PolicyProposalView(
                rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getObject("report_id", UUID.class),
                rs.getTimestamp("report_created_at") == null
                        ? null : rs.getTimestamp("report_created_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("created_by"), read(rs.getString("patch")),
                rs.getString("proposal_sha256"), rs.getString("policy_sha256"),
                rs.getString("target_policy_sha256"), rs.getString("status"),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toInstant(),
                rs.getString("failure"));
    }

    private Optional<PolicyProposalView> findExact(
            UUID reportId, Instant reportCreatedAt, String actor, String serializedPatch) {
        return jdbc.query("""
                SELECT * FROM policy_proposals
                 WHERE report_id=? AND report_created_at=? AND created_by=?
                   AND proposal_sha256 = encode(
                       digest(convert_to(CAST(? AS jsonb)::text, 'UTF8'), 'sha256'), 'hex')
                """, rowMapper(), reportId, Timestamp.from(reportCreatedAt), actor,
                serializedPatch).stream().findFirst();
    }

    private String write(PolicyPatch patch) {
        try {
            return json.writeValueAsString(patch);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize proposal", e);
        }
    }

    private PolicyPatch read(String value) {
        try {
            return json.readValue(value, PolicyPatch.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read proposal", e);
        }
    }
}
