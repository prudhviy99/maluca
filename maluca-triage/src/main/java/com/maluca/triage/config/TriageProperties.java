package com.maluca.triage.config;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "maluca.triage")
public record TriageProperties(
        Security security,
        Privacy privacy,
        Ingest ingest,
        Detection detection,
        Agent agent,
        Retrieval retrieval,
        Retention retention,
        Policy policy,
        Upstreams upstreams) {

    public TriageProperties {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(privacy, "privacy");
        Objects.requireNonNull(ingest, "ingest");
        Objects.requireNonNull(detection, "detection");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(retrieval, "retrieval");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(upstreams, "upstreams");
    }

    public record Security(String apiToken, String internalToken, String mcpToken) {

        public Security {
            requireSafeText(apiToken, 4_096, "security.api-token");
            requireSafeText(internalToken, 4_096, "security.internal-token");
            if (apiToken.equals(internalToken)) {
                throw new IllegalArgumentException(
                        "security.api-token and security.internal-token must differ");
            }
            if (mcpToken != null && !mcpToken.isBlank()) {
                requireSafeText(mcpToken, 4_096, "security.mcp-token");
                if (mcpToken.equals(apiToken) || mcpToken.equals(internalToken)) {
                    throw new IllegalArgumentException(
                            "security.mcp-token must differ from triage credentials");
                }
            }
        }
    }

    public record Privacy(boolean pseudonymizeClientKeys, String hmacKey, int maxPathLength) {

        public Privacy {
            if (maxPathLength < 64 || maxPathLength > 4_096) {
                throw new IllegalArgumentException(
                        "privacy.max-path-length must be between 64 and 4096");
            }
            if (pseudonymizeClientKeys) {
                requireSafeText(hmacKey, 4_096, "privacy.hmac-key");
                if (hmacKey.length() < 16) {
                    throw new IllegalArgumentException(
                            "privacy.hmac-key must contain at least 16 characters when enabled");
                }
            }
        }
    }

    public record Ingest(int maxBatchSize) {

        public Ingest {
            if (maxBatchSize < 1 || maxBatchSize > 5_000) {
                throw new IllegalArgumentException(
                        "ingest.max-batch-size must be between 1 and 5000");
            }
        }
    }

    public record Detection(
            boolean enabled,
            Duration currentWindow,
            Duration baselineWindow,
            Duration pollInterval,
            Duration resolveAfter,
            long minimumMitigated,
            double minimumMitigationShare,
            double mitigationMultiplier,
            long challengeBlockThreshold,
            long trafficVolumeFloor,
            double trafficVolumeMultiplier,
            long redisErrorThreshold,
            int sampleLimit,
            int topValueLimit) {

        public Detection {
            requirePositive(currentWindow, "detection.current-window");
            requirePositive(baselineWindow, "detection.baseline-window");
            requirePositive(pollInterval, "detection.poll-interval");
            requirePositive(resolveAfter, "detection.resolve-after");
            if (baselineWindow.compareTo(currentWindow) <= 0) {
                throw new IllegalArgumentException(
                        "detection.baseline-window must exceed current-window");
            }
            if (minimumMitigated < 1 || challengeBlockThreshold < 1
                    || trafficVolumeFloor < 1 || redisErrorThreshold < 1) {
                throw new IllegalArgumentException(
                        "detection count thresholds must be positive");
            }
            if (!Double.isFinite(minimumMitigationShare)
                    || minimumMitigationShare < 0 || minimumMitigationShare > 1) {
                throw new IllegalArgumentException(
                        "detection.minimum-mitigation-share must be between 0 and 1");
            }
            requireMultiplier(mitigationMultiplier, "detection.mitigation-multiplier");
            requireMultiplier(trafficVolumeMultiplier, "detection.traffic-volume-multiplier");
            if (sampleLimit < 1 || sampleLimit > 200) {
                throw new IllegalArgumentException(
                        "detection.sample-limit must be between 1 and 200");
            }
            if (topValueLimit < 1 || topValueLimit > 100) {
                throw new IllegalArgumentException(
                        "detection.top-value-limit must be between 1 and 100");
            }
        }
    }

    public record Agent(
            boolean enabled,
            Duration pollInterval,
            Duration inferenceTimeout,
            Duration orchestrationTimeout,
            Duration leaseTimeout,
            int maxToolCalls,
            int maxAttempts,
            Duration retryBaseDelay,
            Duration retryMaxDelay,
            int maxBriefCharacters,
            int maxSampleCharacters,
            int maxSampleContributions,
            String model,
            String promptVersion,
            int repairAttempts,
            int maxSummaryWords,
            int maxEvidenceItems,
            List<String> allowedTools) {

        private static final Duration MAX_INFERENCE_TIMEOUT = Duration.ofMinutes(15);
        private static final Duration MAX_ORCHESTRATION_TIMEOUT = Duration.ofMinutes(30);
        private static final Duration MAX_LEASE_TIMEOUT = Duration.ofHours(24);

        public Agent {
            requirePositive(pollInterval, "agent.poll-interval");
            requirePositive(inferenceTimeout, "agent.inference-timeout");
            requirePositive(orchestrationTimeout, "agent.orchestration-timeout");
            requirePositive(leaseTimeout, "agent.lease-timeout");
            requirePositive(retryBaseDelay, "agent.retry-base-delay");
            requirePositive(retryMaxDelay, "agent.retry-max-delay");
            if (orchestrationTimeout.compareTo(inferenceTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "agent.orchestration-timeout must exceed inference-timeout");
            }
            if (inferenceTimeout.compareTo(MAX_INFERENCE_TIMEOUT) > 0) {
                throw new IllegalArgumentException(
                        "agent.inference-timeout cannot exceed " + MAX_INFERENCE_TIMEOUT);
            }
            if (orchestrationTimeout.compareTo(MAX_ORCHESTRATION_TIMEOUT) > 0) {
                throw new IllegalArgumentException(
                        "agent.orchestration-timeout cannot exceed " + MAX_ORCHESTRATION_TIMEOUT);
            }
            if (leaseTimeout.compareTo(MAX_LEASE_TIMEOUT) > 0) {
                throw new IllegalArgumentException(
                        "agent.lease-timeout cannot exceed " + MAX_LEASE_TIMEOUT);
            }
            if (leaseTimeout.compareTo(orchestrationTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "agent.lease-timeout must exceed orchestration-timeout");
            }
            if (maxToolCalls < 0 || maxToolCalls > 50) {
                throw new IllegalArgumentException("agent.max-tool-calls must be between 0 and 50");
            }
            if (maxAttempts < 1 || maxAttempts > 20) {
                throw new IllegalArgumentException("agent.max-attempts must be between 1 and 20");
            }
            if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
                throw new IllegalArgumentException("agent.retry-max-delay cannot be shorter than retry-base-delay");
            }
            if (maxBriefCharacters < 4_096 || maxBriefCharacters > 100_000) {
                throw new IllegalArgumentException("agent.max-brief-characters must be between 4096 and 100000");
            }
            if (maxSampleCharacters < 512 || maxSampleCharacters > maxBriefCharacters) {
                throw new IllegalArgumentException(
                        "agent.max-sample-characters must be between 512 and max-brief-characters");
            }
            if (maxSampleContributions < 1 || maxSampleContributions > 64) {
                throw new IllegalArgumentException("agent.max-sample-contributions must be between 1 and 64");
            }
            requireSafeText(model, 256, "agent.model");
            requireSafeText(promptVersion, 64, "agent.prompt-version");
            if (repairAttempts < 0 || repairAttempts > 3) {
                throw new IllegalArgumentException("agent.repair-attempts must be between 0 and 3");
            }
            if (maxSummaryWords < 10 || maxSummaryWords > 1_000) {
                throw new IllegalArgumentException("agent.max-summary-words must be between 10 and 1000");
            }
            if (maxEvidenceItems < 0 || maxEvidenceItems > 100) {
                throw new IllegalArgumentException("agent.max-evidence-items must be between 0 and 100");
            }
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            if (allowedTools.size() > 50 || new HashSet<>(allowedTools).size() != allowedTools.size()) {
                throw new IllegalArgumentException(
                        "agent.allowed-tools must contain at most 50 unique names");
            }
            allowedTools.forEach(tool -> requireSafeText(tool, 128, "agent.allowed-tools entry"));
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    public record Retrieval(
            int topK,
            double similarityThreshold,
            String runbookLocation,
            boolean ingestOnStartup,
            int embeddingDimensions,
            String embeddingModel) {

        public Retrieval {
            if (topK < 1 || topK > 12) {
                throw new IllegalArgumentException("retrieval.top-k must be between 1 and 12");
            }
            if (!Double.isFinite(similarityThreshold)
                    || similarityThreshold < 0 || similarityThreshold > 1) {
                throw new IllegalArgumentException(
                        "retrieval.similarity-threshold must be between 0 and 1");
            }
            requireSafeText(runbookLocation, 2_048, "retrieval.runbook-location");
            if (embeddingDimensions < 1 || embeddingDimensions > 4_096) {
                throw new IllegalArgumentException(
                        "retrieval.embedding-dimensions must be between 1 and 4096");
            }
            if (embeddingModel == null || embeddingModel.isBlank()
                    || embeddingModel.length() > 256
                    || embeddingModel.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "retrieval.embedding-model must contain 1 to 256 safe characters");
            }
        }
    }

    public record Retention(Duration decisions, String purgeCron) {

        public Retention {
            requirePositive(decisions, "retention.decisions");
            if (decisions.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("retention.decisions cannot exceed 365 days");
            }
            requireSafeText(purgeCron, 128, "retention.purge-cron");
        }
    }

    public record Policy(
            Path file,
            int defaultObserveMin,
            int defaultSoftLimitMin,
            int defaultHardLimitMin,
            int defaultChallengeMin,
            int defaultBlockMin,
            int backupRetention,
            boolean applyEnabled) {

        public Policy {
            Objects.requireNonNull(file, "policy.file");
            int[] bands = { defaultObserveMin, defaultSoftLimitMin, defaultHardLimitMin,
                    defaultChallengeMin, defaultBlockMin };
            for (int band : bands) {
                if (band < 0 || band > 100) {
                    throw new IllegalArgumentException(
                            "policy default band thresholds must be between 0 and 100");
                }
            }
            for (int index = 1; index < bands.length; index++) {
                if (bands[index] <= bands[index - 1]) {
                    throw new IllegalArgumentException(
                            "policy default band thresholds must be strictly increasing");
                }
            }
            if (backupRetention < 1 || backupRetention > 1_000) {
                throw new IllegalArgumentException(
                        "policy.backup-retention must be between 1 and 1000");
            }
        }
    }

    public record Upstreams(
            String proxyBaseUrl,
            String proxyAdminToken,
            Duration proxyConnectTimeout,
            Duration proxyReadTimeout,
            String prometheusBaseUrl) {

        private static final Duration MIN_PROXY_TIMEOUT = Duration.ofMillis(100);
        private static final Duration MAX_PROXY_CONNECT_TIMEOUT = Duration.ofSeconds(30);
        private static final Duration MAX_PROXY_READ_TIMEOUT = Duration.ofSeconds(60);

        public Upstreams {
            requireHttpOrigin(proxyBaseUrl, "upstreams.proxy-base-url");
            requireSafeText(proxyAdminToken, 4_096, "upstreams.proxy-admin-token");
            requireHttpOrigin(prometheusBaseUrl, "upstreams.prometheus-base-url");
            requireBounded(proxyConnectTimeout, MIN_PROXY_TIMEOUT,
                    MAX_PROXY_CONNECT_TIMEOUT, "upstreams.proxy-connect-timeout");
            requireBounded(proxyReadTimeout, MIN_PROXY_TIMEOUT,
                    MAX_PROXY_READ_TIMEOUT, "upstreams.proxy-read-timeout");
        }

        private static void requireBounded(
                Duration value, Duration minimum, Duration maximum, String name) {
            if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " must be between "
                        + minimum + " and " + maximum);
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireMultiplier(double value, String name) {
        if (!Double.isFinite(value) || value < 1 || value > 1_000) {
            throw new IllegalArgumentException(name + " must be between 1 and 1000");
        }
    }

    private static void requireSafeText(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must contain 1 to " + maximum + " safe characters");
        }
    }

    private static void requireHttpOrigin(String value, String name) {
        requireSafeText(value, 2_048, name);
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || (path != null && !path.isBlank() && !"/".equals(path))) {
                throw new IllegalArgumentException(name + " must be an absolute HTTP(S) origin");
            }
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith(name)) {
                throw invalid;
            }
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) origin", invalid);
        }
    }
}
