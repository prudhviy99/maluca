package com.maluca.triage.incident;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.incident.IncidentStats;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.contracts.incident.IncidentView;

@Repository
public class IncidentRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;

    public IncidentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
    }

    public IncidentView openOrTouch(String policyName, String route, IncidentTrigger trigger,
                                    IncidentStats stats, Instant now) {
        String sql = """
                INSERT INTO incidents (id, opened_at, last_active_at, policy_name, policy_route,
                                       trigger, status, stats)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', CAST(? AS jsonb))
                ON CONFLICT DO NOTHING
                """;
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(sql, id, Timestamp.from(now), Timestamp.from(now), policyName, route,
                trigger.name(), write(stats));
        if (inserted == 0) {
            jdbc.update("""
                    UPDATE incidents SET last_active_at=?
                     WHERE policy_name=? AND closed_at IS NULL
                       AND status NOT IN ('RESOLVED','DISMISSED','APPLY_FAILED')
                    """, Timestamp.from(now), policyName);
            return findActive(policyName).orElseThrow();
        }
        return find(id).orElseThrow();
    }

    public Optional<IncidentView> find(UUID id) {
        return jdbc.query("SELECT * FROM incidents WHERE id=?", rowMapper(), id).stream().findFirst();
    }

    public Optional<IncidentView> findActive(String policyName) {
        return jdbc.query("""
                SELECT * FROM incidents WHERE policy_name=? AND closed_at IS NULL
                  AND status NOT IN ('RESOLVED','DISMISSED','APPLY_FAILED')
                """, rowMapper(), policyName).stream().findFirst();
    }

    public List<IncidentView> findRecent(IncidentStatus status, int limit) {
        if (status == null) {
            return jdbc.query("SELECT * FROM incidents ORDER BY opened_at DESC LIMIT ?", rowMapper(), limit);
        }
        return jdbc.query("SELECT * FROM incidents WHERE status=? ORDER BY opened_at DESC LIMIT ?",
                rowMapper(), status.name(), limit);
    }

    public List<IncidentView> findUnresolved() {
        return jdbc.query("""
                SELECT * FROM incidents WHERE closed_at IS NULL
                  AND status NOT IN ('RESOLVED','DISMISSED','APPLY_FAILED')
                ORDER BY opened_at
                """, rowMapper());
    }

    public List<IncidentView> findInactiveUnresolved(Instant inactiveBefore) {
        return jdbc.query("""
                SELECT * FROM incidents WHERE closed_at IS NULL
                  AND status IN ('OPEN','TRIAGED','APPLIED')
                  AND last_active_at < ?
                ORDER BY opened_at
                """, rowMapper(), Timestamp.from(inactiveBefore));
    }

    /** Atomically claims one eligible incident across any number of triage replicas. */
    public Optional<IncidentClaim> claimNextEligible(Instant now) {
        UUID leaseId = UUID.randomUUID();
        String sql = """
                WITH candidate AS (
                    SELECT id FROM incidents WHERE status='OPEN'
                       AND (triage_next_attempt_at IS NULL OR triage_next_attempt_at <= ?)
                     ORDER BY COALESCE(triage_next_attempt_at, opened_at), opened_at
                     FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE incidents i
                   SET status='TRIAGING', triage_lease_id=?, triage_claimed_at=?,
                       triage_attempts=triage_attempts+1, triage_next_attempt_at=NULL,
                       triage_failure=NULL, version=version+1
                  FROM candidate c WHERE i.id=c.id RETURNING i.*
                """;
        return jdbc.query(sql, claimRowMapper(), Timestamp.from(now), leaseId, Timestamp.from(now))
                .stream().findFirst();
    }

    /** Operator-triggered triage bypasses backoff and can retry a terminal failure. */
    public Optional<IncidentClaim> claimForManualTriage(UUID id, Instant now) {
        UUID leaseId = UUID.randomUUID();
        return jdbc.query("""
                UPDATE incidents
                   SET status='TRIAGING', triage_lease_id=?, triage_claimed_at=?,
                       triage_attempts=triage_attempts+1, triage_next_attempt_at=NULL,
                       triage_failure=NULL, version=version+1
                 WHERE id=? AND status IN ('OPEN','TRIAGE_FAILED')
                   AND triage_lease_id IS NULL
                 RETURNING *
                """, claimRowMapper(), leaseId, Timestamp.from(now), id).stream().findFirst();
    }

    /** Locks and verifies a lease inside the caller's transaction before report writes. */
    public boolean lockClaim(UUID id, UUID leaseId) {
        return !jdbc.queryForList("""
                SELECT id FROM incidents
                 WHERE id=? AND status='TRIAGING' AND triage_lease_id=?
                 FOR UPDATE
                """, UUID.class, id, leaseId).isEmpty();
    }

    public boolean completeClaim(UUID id, UUID leaseId) {
        return jdbc.update("""
                UPDATE incidents
                   SET status='TRIAGED', triage_lease_id=NULL, triage_claimed_at=NULL,
                       triage_next_attempt_at=NULL, triage_failure=NULL, version=version+1
                 WHERE id=? AND status='TRIAGING' AND triage_lease_id=?
                """, id, leaseId) == 1;
    }

    /** Releases an owned claim into backoff or terminal manual-review state. */
    public Optional<TriageFailure> recordTriageFailure(
            IncidentClaim claim, TriageRetryPolicy.FailurePlan plan, String failure) {
        int updated = applyFailure(
                claim.incident().id(), claim.leaseId(), claim.claimedAt(), claim.attempt(),
                plan, failure, false);
        return updated == 1
                ? Optional.of(new TriageFailure(
                        claim.incident().id(), plan.status(), claim.attempt(), plan.nextAttemptAt()))
                : Optional.empty();
    }

    /**
     * Reclaims leases whose holder disappeared. Exact lease/timestamp predicates fence
     * this scan against a concurrent completion or a newer claim.
     */
    public List<TriageFailure> reclaimExpiredClaims(
            Instant staleBefore, Instant now, TriageRetryPolicy retryPolicy) {
        List<ExpiredClaim> expired = jdbc.query("""
                SELECT id, triage_lease_id, triage_claimed_at, triage_attempts
                  FROM incidents
                 WHERE status='TRIAGING'
                   AND (triage_claimed_at IS NULL OR triage_claimed_at <= ?)
                 ORDER BY triage_claimed_at NULLS FIRST, opened_at
                 LIMIT 100
                """, (rs, rowNum) -> new ExpiredClaim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("triage_lease_id", UUID.class),
                        rs.getTimestamp("triage_claimed_at") == null
                                ? null : rs.getTimestamp("triage_claimed_at").toInstant(),
                        Math.max(1, rs.getInt("triage_attempts"))),
                Timestamp.from(staleBefore));
        List<TriageFailure> reclaimed = new java.util.ArrayList<>();
        for (ExpiredClaim claim : expired) {
            var plan = retryPolicy.afterFailure(claim.attempt(), now);
            if (applyFailure(claim.id(), claim.leaseId(), claim.claimedAt(), claim.attempt(),
                    plan, "triage claim lease expired", true) == 1) {
                reclaimed.add(new TriageFailure(
                        claim.id(), plan.status(), claim.attempt(), plan.nextAttemptAt()));
            }
        }
        return List.copyOf(reclaimed);
    }

    public boolean transition(UUID id, long expectedVersion, IncidentStatus from, IncidentStatus to) {
        return jdbc.update("""
                UPDATE incidents SET status=?, version=version+1 WHERE id=? AND version=? AND status=?
                """, to.name(), id, expectedVersion, from.name()) == 1;
    }

    /** Closes only the exact terminal triage failure reviewed by an operator. */
    public boolean dismissTriageFailure(UUID id, long expectedVersion, Instant closedAt) {
        return jdbc.update("""
                UPDATE incidents
                   SET status='DISMISSED', closed_at=?, triage_next_attempt_at=NULL,
                       triage_lease_id=NULL, triage_claimed_at=NULL, version=version+1
                 WHERE id=? AND version=? AND status='TRIAGE_FAILED' AND closed_at IS NULL
                """, Timestamp.from(closedAt), id, expectedVersion) == 1;
    }

    public void setStatus(UUID id, IncidentStatus status) {
        jdbc.update("UPDATE incidents SET status=?, version=version+1 WHERE id=?", status.name(), id);
    }

    public void resolve(UUID id, Instant closedAt) {
        jdbc.update("""
                UPDATE incidents SET status='RESOLVED', closed_at=?, version=version+1
                 WHERE id=? AND closed_at IS NULL
                """, Timestamp.from(closedAt), id);
    }

    private RowMapper<IncidentView> rowMapper() {
        return (rs, rowNum) -> new IncidentView(
                rs.getObject("id", UUID.class), rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                rs.getString("policy_name"), rs.getString("policy_route"),
                IncidentTrigger.valueOf(rs.getString("trigger")),
                IncidentStatus.valueOf(rs.getString("status")),
                readStats(rs.getString("stats")), rs.getLong("version"),
                timestamp(rs, "triage_claimed_at"), rs.getInt("triage_attempts"),
                timestamp(rs, "triage_next_attempt_at"), rs.getString("triage_failure"));
    }

    private RowMapper<IncidentClaim> claimRowMapper() {
        RowMapper<IncidentView> incidents = rowMapper();
        return (rs, rowNum) -> new IncidentClaim(
                incidents.mapRow(rs, rowNum), rs.getObject("triage_lease_id", UUID.class),
                rs.getInt("triage_attempts"), rs.getTimestamp("triage_claimed_at").toInstant());
    }

    private int applyFailure(UUID id, UUID leaseId, Instant claimedAt, int attempts,
                             TriageRetryPolicy.FailurePlan plan, String failure,
                             boolean matchClaimedAt) {
        String claimedPredicate = matchClaimedAt
                ? " AND triage_claimed_at IS NOT DISTINCT FROM ?" : "";
        String sql = """
                UPDATE incidents
                   SET status=?, triage_lease_id=NULL, triage_claimed_at=NULL,
                       triage_attempts=GREATEST(triage_attempts, ?),
                       triage_next_attempt_at=?, triage_failure=?, version=version+1
                 WHERE id=? AND status='TRIAGING'
                   AND triage_lease_id IS NOT DISTINCT FROM ?
                """ + claimedPredicate;
        Object next = plan.nextAttemptAt() == null ? null : Timestamp.from(plan.nextAttemptAt());
        String safeFailure = failure == null ? "triage failed"
                : failure.substring(0, Math.min(2_000, failure.length()));
        if (matchClaimedAt) {
            Object claimed = claimedAt == null ? null : Timestamp.from(claimedAt);
            return jdbc.update(sql, plan.status().name(), Math.max(1, attempts), next,
                    safeFailure, id, leaseId, claimed);
        }
        return jdbc.update(sql, plan.status().name(), Math.max(1, attempts), next,
                safeFailure, id, leaseId);
    }

    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record ExpiredClaim(UUID id, UUID leaseId, Instant claimedAt, int attempt) {
    }

    public record TriageFailure(
            UUID incidentId,
            IncidentStatus status,
            int attempt,
            Instant nextAttemptAt) {
        public boolean terminal() {
            return status == IncidentStatus.TRIAGE_FAILED;
        }
    }

    private IncidentStats readStats(String value) {
        try {
            return json.readValue(value, IncidentStats.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read incident stats", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize incident state", e);
        }
    }
}
