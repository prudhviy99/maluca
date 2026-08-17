package com.maluca.triage.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.triage.TriageTestFixtures;

@Testcontainers(disabledWithoutDocker = true)
class IncidentRepositoryLeaseTest {

    private static final DockerImageName IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.5-pg16-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("maluca")
            .withUsername("maluca")
            .withPassword("maluca-test");

    private static JdbcTemplate jdbc;
    private static IncidentRepository repository;
    private static TriageRetryPolicy retryPolicy;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new IncidentRepository(
                jdbc, JsonMapper.builder().addModule(new JavaTimeModule()).build());
        retryPolicy = new TriageRetryPolicy(TriageTestFixtures.properties(Path.of("policies.yml")));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incidents");
    }

    @Test
    void leasesBackoffExpiredClaimsAndTerminallyFencesPoisonIncidents() {
        Instant now = Instant.parse("2026-08-13T12:00:00Z");
        var stats = TriageTestFixtures.incident().stats();
        var delayed = repository.openOrTouch(
                "delayed", "/delayed", IncidentTrigger.MITIGATION_SPIKE, stats, now.minusSeconds(60));
        jdbc.update("UPDATE incidents SET triage_next_attempt_at=? WHERE id=?",
                java.sql.Timestamp.from(now.plusSeconds(600)), delayed.id());
        var eligible = repository.openOrTouch(
                "eligible", "/eligible", IncidentTrigger.MITIGATION_SPIKE, stats, now.minusSeconds(30));

        IncidentClaim first = repository.claimNextEligible(now).orElseThrow();
        assertThat(first.incident().id()).isEqualTo(eligible.id());
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(first.incident().triageClaimedAt()).isEqualTo(now);
        assertThat(repository.lockClaim(first.incident().id(), first.leaseId())).isTrue();

        var firstPlan = retryPolicy.afterFailure(first.attempt(), now);
        assertThat(repository.recordTriageFailure(first, firstPlan, "ollama timeout")).isPresent();
        assertThat(repository.claimNextEligible(now.plusSeconds(1))).isEmpty();

        IncidentClaim second = repository.claimNextEligible(now.plusSeconds(6)).orElseThrow();
        assertThat(second.incident().id()).isEqualTo(eligible.id());
        assertThat(second.attempt()).isEqualTo(2);

        Instant recoveryTime = now.plusSeconds(60);
        var reclaimed = repository.reclaimExpiredClaims(
                now.plusSeconds(7), recoveryTime, retryPolicy);
        assertThat(reclaimed).singleElement().satisfies(outcome -> {
            assertThat(outcome.incidentId()).isEqualTo(eligible.id());
            assertThat(outcome.status()).isEqualTo(IncidentStatus.OPEN);
            assertThat(outcome.nextAttemptAt()).isEqualTo(recoveryTime.plusSeconds(10));
        });
        assertThat(repository.lockClaim(second.incident().id(), second.leaseId())).isFalse();

        IncidentClaim third = repository.claimNextEligible(recoveryTime.plusSeconds(11)).orElseThrow();
        assertThat(third.attempt()).isEqualTo(3);
        var terminal = repository.recordTriageFailure(
                third, retryPolicy.afterFailure(third.attempt(), recoveryTime), "poison input")
                .orElseThrow();
        assertThat(terminal.terminal()).isTrue();

        var failed = repository.find(eligible.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(IncidentStatus.TRIAGE_FAILED);
        assertThat(failed.triageFailure()).isEqualTo("poison input");
        assertThat(failed.triageNextAttemptAt()).isNull();
        assertThat(repository.claimNextEligible(recoveryTime.plusSeconds(600)))
                .map(claim -> claim.incident().id())
                .contains(delayed.id());

        IncidentClaim manual = repository.claimForManualTriage(eligible.id(), recoveryTime.plusSeconds(12))
                .orElseThrow();
        assertThat(manual.attempt()).isEqualTo(4);
        assertThat(manual.incident().status()).isEqualTo(IncidentStatus.TRIAGING);
    }

    @Test
    void anomalyHeartbeatDoesNotInvalidateAReviewedIncidentVersion() {
        Instant now = Instant.parse("2026-08-13T12:00:00Z");
        var stats = TriageTestFixtures.incident().stats();
        var opened = repository.openOrTouch(
                "reviewed", "/reviewed", IncidentTrigger.MITIGATION_SPIKE, stats, now);
        jdbc.update("UPDATE incidents SET status='TRIAGED', version=7 WHERE id=?", opened.id());

        var touched = repository.openOrTouch(
                "reviewed", "/reviewed", IncidentTrigger.MITIGATION_SPIKE,
                stats, now.plusSeconds(15));

        assertThat(touched.version()).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT last_active_at FROM incidents WHERE id=?",
                java.sql.Timestamp.class, opened.id()).toInstant())
                .isEqualTo(now.plusSeconds(15));
    }
}
