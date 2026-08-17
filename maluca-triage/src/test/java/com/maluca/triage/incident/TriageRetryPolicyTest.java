package com.maluca.triage.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.triage.TriageTestFixtures;

class TriageRetryPolicyTest {

    private final TriageRetryPolicy policy = new TriageRetryPolicy(
            TriageTestFixtures.properties(Path.of("policies.yml")));
    private final Instant now = Instant.parse("2026-08-13T12:00:00Z");

    @Test
    void appliesExponentialBackoffBeforeTheAttemptLimit() {
        assertThat(policy.afterFailure(1, now))
                .isEqualTo(new TriageRetryPolicy.FailurePlan(
                        IncidentStatus.OPEN, now.plusSeconds(5)));
        assertThat(policy.afterFailure(2, now))
                .isEqualTo(new TriageRetryPolicy.FailurePlan(
                        IncidentStatus.OPEN, now.plusSeconds(10)));
    }

    @Test
    void movesPoisonIncidentsToManualReviewAtTheLimit() {
        var plan = policy.afterFailure(3, now);

        assertThat(plan.status()).isEqualTo(IncidentStatus.TRIAGE_FAILED);
        assertThat(plan.nextAttemptAt()).isNull();
        assertThat(plan.terminal()).isTrue();
    }
}
