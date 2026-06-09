package com.maluca.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.maluca.TestFixtures;
import com.maluca.model.RiskSignals;
import com.maluca.model.ScoreResult;
import com.maluca.model.UaClass;

class WeightedLinearScorerTest {

    private final WeightedLinearScorer scorer = new WeightedLinearScorer(TestFixtures.defaultProperties());

    @Test
    void quietBrowserScoresZero() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(3).sustained60s(10).distinctPaths30s(4)
                .uaClass(UaClass.BROWSER)
                .build());

        assertThat(result.score()).isZero();
        assertThat(result.contributions()).isEmpty();
    }

    @Test
    void valueAtThresholdContributesNothing() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(30) // exactly the threshold
                .uaClass(UaClass.BROWSER)
                .build());

        assertThat(result.contributions()).doesNotContainKey("burst_10s");
    }

    @Test
    void doubleThresholdContributesFullWeight() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(60) // 2x threshold of 30
                .uaClass(UaClass.BROWSER)
                .build());

        assertThat(result.contributions().get("burst_10s")).isEqualTo(40.0);
        assertThat(result.score()).isEqualTo(40);
    }

    @Test
    void perSignalContributionIsCappedAt1point5xWeight() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(10_000)
                .uaClass(UaClass.BROWSER)
                .build());

        assertThat(result.contributions().get("burst_10s")).isEqualTo(60.0); // 40 * 1.5
    }

    @Test
    void scoreIsMonotonicInBurstRate() {
        int previous = -1;
        for (long burst : new long[]{10, 31, 40, 60, 90, 200}) {
            int score = scorer.score(RiskSignals.builder()
                    .burst10s(burst).uaClass(UaClass.BROWSER).build()).score();
            assertThat(score).isGreaterThanOrEqualTo(previous);
            previous = score;
        }
    }

    @Test
    void totalIsClampedTo100() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(1000).sustained60s(5000).distinctPaths30s(500)
                .sensitiveHits60s(400).fourxx60s(100).headerAnomalies(4)
                .uaClass(UaClass.KNOWN_BAD_BOT)
                .limitExceeded(true).priorEscalation(true)
                .build());

        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void denylistAloneForcesMaxScore() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .uaClass(UaClass.BROWSER).onDenylist(true).build());

        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void verifiedBotUaIsNotPenalized() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .uaClass(UaClass.VERIFIED_BOT).build());

        assertThat(result.contributions().keySet())
                .noneMatch(k -> k.startsWith("ua_class"));
    }

    @Test
    void everyContributionIsNamedAndExplainable() {
        ScoreResult result = scorer.score(RiskSignals.builder()
                .burst10s(60).sensitiveHits60s(50).headerAnomalies(2)
                .uaClass(UaClass.SCRIPT_CLIENT).limitExceeded(true)
                .build());

        assertThat(result.contributions()).containsKeys(
                "burst_10s", "sensitive_60s", "header_anomaly",
                "ua_class_script_client", "limit_exceeded");
        double sum = result.contributions().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(result.score()).isEqualTo((int) Math.round(Math.min(sum, 100)));
    }
}
