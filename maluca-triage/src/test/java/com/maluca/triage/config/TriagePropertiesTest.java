package com.maluca.triage.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.maluca.triage.TriageTestFixtures;

class TriagePropertiesTest {

    @Test
    void orchestrationMustOutliveInferenceAndFitInsideLease() {
        TriageProperties.Agent base = properties().agent();

        assertThatThrownBy(() -> copyAgent(
                base, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orchestration-timeout must exceed inference-timeout");
        assertThatThrownBy(() -> copyAgent(
                base, Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-timeout must exceed orchestration-timeout");
    }

    @Test
    void inferenceOrchestrationAndLeaseHaveFiniteConfigurationCeilings() {
        TriageProperties.Agent base = properties().agent();

        assertThatThrownBy(() -> copyAgent(
                base, Duration.ofMinutes(16), Duration.ofMinutes(20), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inference-timeout cannot exceed");
        assertThatThrownBy(() -> copyAgent(
                base, Duration.ofMinutes(10), Duration.ofMinutes(31), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orchestration-timeout cannot exceed");
        assertThatThrownBy(() -> copyAgent(
                base, Duration.ofMinutes(10), Duration.ofMinutes(20), Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-timeout cannot exceed");
    }

    @Test
    void embeddingModelIdentityMustBeSafeAndNonBlank() {
        TriageProperties base = properties();

        assertThatThrownBy(() -> new TriageProperties.Retrieval(
                base.retrieval().topK(), base.retrieval().similarityThreshold(),
                base.retrieval().runbookLocation(), base.retrieval().ingestOnStartup(),
                base.retrieval().embeddingDimensions(), "bad\nmodel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
    }

    @Test
    void credentialsPrivacyAndOriginsFailClosed() {
        assertThatThrownBy(() -> new TriageProperties.Security("same", "same", "mcp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
        assertThatThrownBy(() -> new TriageProperties.Privacy(true, "short", 512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 16");
        assertThatThrownBy(() -> new TriageProperties.Upstreams(
                "http://user:password@proxy.example", "admin", Duration.ofSeconds(2),
                Duration.ofSeconds(5), "http://prometheus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP(S) origin");
    }

    @Test
    void detectorAndRetrievalBoundsRejectUnsafeTuning() {
        TriageProperties base = properties();
        TriageProperties.Detection detection = base.detection();
        assertThatThrownBy(() -> new TriageProperties.Detection(
                detection.enabled(), detection.currentWindow(), detection.baselineWindow(),
                detection.pollInterval(), detection.resolveAfter(), detection.minimumMitigated(),
                1.1, detection.mitigationMultiplier(), detection.challengeBlockThreshold(),
                detection.trafficVolumeFloor(), detection.trafficVolumeMultiplier(),
                detection.redisErrorThreshold(), detection.sampleLimit(), detection.topValueLimit()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-mitigation-share");
        assertThatThrownBy(() -> new TriageProperties.Retrieval(
                13, base.retrieval().similarityThreshold(), base.retrieval().runbookLocation(),
                base.retrieval().ingestOnStartup(), base.retrieval().embeddingDimensions(),
                base.retrieval().embeddingModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top-k");
    }

    private static TriageProperties properties() {
        return TriageTestFixtures.properties(Path.of("policies.yml"));
    }

    private static TriageProperties.Agent copyAgent(
            TriageProperties.Agent base,
            Duration inference,
            Duration orchestration,
            Duration lease) {
        return new TriageProperties.Agent(
                base.enabled(), base.pollInterval(), inference, orchestration, lease,
                base.maxToolCalls(), base.maxAttempts(), base.retryBaseDelay(),
                base.retryMaxDelay(), base.maxBriefCharacters(),
                base.maxSampleCharacters(), base.maxSampleContributions(), base.model(),
                base.promptVersion(), base.repairAttempts(), base.maxSummaryWords(),
                base.maxEvidenceItems(), base.allowedTools());
    }
}
