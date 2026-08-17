package com.maluca.triage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.triage.policy.PolicyProposalRepository;

@Testcontainers(disabledWithoutDocker = true)
class TriageMigrationTest {

    private static final DockerImageName IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.5-pg16-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("maluca")
            .withUsername("maluca")
            .withPassword("maluca-test");

    @Test
    void migrationCreatesPgvectorAndAllControlPlaneTables() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'")) {
                assertThat(rs.next()).isTrue();
            }
            try (var rs = statement.executeQuery("SELECT extversion FROM pg_extension WHERE extname='pgcrypto'")) {
                assertThat(rs.next()).isTrue();
            }
            try (var rs = statement.executeQuery("""
                    SELECT table_name FROM information_schema.tables WHERE table_schema='public'
                    """)) {
                ListAccumulator tables = new ListAccumulator();
                while (rs.next()) tables.add(rs.getString(1));
                assertThat(tables.values).contains("decisions", "incidents", "triage_reports",
                        "policy_proposals", "audit_events", "runbook_chunks");
            }
            try (var rs = statement.executeQuery("""
                    SELECT format_type(a.atttypid, a.atttypmod)
                      FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                     WHERE c.relname='runbook_chunks' AND a.attname='embedding'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("vector(768)");
            }
            try (var rs = statement.executeQuery("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema='public' AND table_name='incidents'
                    """)) {
                ListAccumulator columns = new ListAccumulator();
                while (rs.next()) columns.add(rs.getString(1));
                assertThat(columns.values).contains(
                        "triage_lease_id", "triage_claimed_at", "triage_attempts",
                        "triage_next_attempt_at", "triage_failure");
            }
            try (var rs = statement.executeQuery("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema='public' AND table_name='policy_proposals'
                    """)) {
                ListAccumulator columns = new ListAccumulator();
                while (rs.next()) columns.add(rs.getString(1));
                assertThat(columns.values).contains(
                        "proposal_sha256", "policy_sha256", "target_policy_sha256",
                        "report_created_at");
            }
        }

        verifyProposalGenerationBinding();
    }

    private void verifyProposalGenerationBinding() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var repository = new PolicyProposalRepository(jdbc, new ObjectMapper());
        var incidentId = java.util.UUID.randomUUID();
        var reportId = java.util.UUID.randomUUID();
        var reportCreatedAt = java.time.Instant.parse("2026-08-13T12:00:00Z");
        jdbc.update("""
                INSERT INTO incidents
                    (id, opened_at, last_active_at, policy_name, policy_route, trigger, status, stats)
                VALUES (?, ?, ?, 'migration-test', '/migration', 'MITIGATION_SPIKE', 'TRIAGED', '{}'::jsonb)
                """, incidentId, java.sql.Timestamp.from(reportCreatedAt),
                java.sql.Timestamp.from(reportCreatedAt));
        jdbc.update("""
                INSERT INTO triage_reports
                    (id, incident_id, created_at, model, prompt_version, classification,
                     confidence, summary, valid, raw_response)
                VALUES (?, ?, ?, 'test-model', 'v1', 'UNKNOWN', 'LOW', 'test', true, '{}')
                """, reportId, incidentId, java.sql.Timestamp.from(reportCreatedAt));
        PolicyPatch patch = new PolicyPatch(
                "migration-test", "/migration", "DRY_RUN", null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                null, "provenance test");

        var proposal = repository.create(
                incidentId, reportId, reportCreatedAt, "test-actor", patch, "a".repeat(64));

        assertThat(proposal.reportCreatedAt()).isEqualTo(reportCreatedAt);
        assertThat(repository.findProposed(proposal.id(), incidentId)).isPresent();

        var nextGeneration = reportCreatedAt.plusSeconds(1);
        jdbc.update("UPDATE triage_reports SET created_at=? WHERE id=?",
                java.sql.Timestamp.from(nextGeneration), reportId);

        assertThat(repository.findProposed(proposal.id(), incidentId)).isEmpty();
        assertThat(repository.quarantineStaleProposed(
                incidentId, reportId, nextGeneration)).isEqualTo(1);
        assertThat(repository.find(proposal.id()).orElseThrow().status())
                .isEqualTo("REJECTED_STALE_PROVENANCE");
    }

    private static final class ListAccumulator {
        private final java.util.List<String> values = new java.util.ArrayList<>();
        void add(String value) { values.add(value); }
    }
}
