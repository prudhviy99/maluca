package com.maluca.triage.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.triage.TriageTestFixtures;

class AnomalyRuleEvaluatorTest {

    private final AnomalyRuleEvaluator evaluator =
            new AnomalyRuleEvaluator(TriageTestFixtures.properties(Path.of("policies.yml")));

    @Test
    void redisDegradationHasPriority() {
        var result = evaluator.evaluate(window(200, 100, 50, 3, 1_000, 10));
        assertThat(result).contains(IncidentTrigger.REDIS_DEGRADATION);
    }

    @Test
    void opensForChallengeAndBlockSurge() {
        assertThat(evaluator.evaluate(window(100, 25, 20, 0, 1_000, 10)))
                .contains(IncidentTrigger.CHALLENGE_BLOCK_SURGE);
    }

    @Test
    void opensForMitigationShareAboveRelativeAndAbsoluteFloors() {
        assertThat(evaluator.evaluate(window(100, 40, 5, 0, 1_000, 20)))
                .contains(IncidentTrigger.MITIGATION_SPIKE);
    }

    @Test
    void baselineZeroStillRequiresAbsoluteFloors() {
        assertThat(evaluator.evaluate(window(20, 10, 0, 0, 0, 0))).isEmpty();
    }

    @Test
    void detectsVolumeSpikeEvenWhenClientsEvadeMitigation() {
        assertThat(evaluator.evaluate(window(500, 0, 0, 0, 900, 0)))
                .contains(IncidentTrigger.TRAFFIC_VOLUME_SURGE);
    }

    @Test
    void ordinaryWindowDoesNotOpenIncident() {
        assertThat(evaluator.evaluate(window(60, 2, 0, 0, 900, 30))).isEmpty();
    }

    private static WindowAggregate window(long total, long mitigated, long challenge,
                                           long redis, long baselineTotal, long baselineMitigated) {
        return new WindowAggregate("api", "/api/**", total, mitigated, challenge, redis,
                50, 90, 10, 5, baselineTotal, baselineMitigated);
    }
}
