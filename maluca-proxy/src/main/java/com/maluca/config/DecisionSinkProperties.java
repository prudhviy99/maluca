package com.maluca.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Isolated configuration for the lossy proxy-to-triage decision channel.
 *
 * <p>The sink is deliberately disabled by default: a standalone proxy never
 * depends on the triage control plane. When enabled, all bounds are explicit
 * so a failed control plane cannot grow proxy memory use without limit.
 */
@ConfigurationProperties(prefix = "maluca.decision-sink")
public record DecisionSinkProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8082/internal/v1/decisions") URI endpoint,
        @DefaultValue("X-Maluca-Internal-Token") String authHeader,
        @DefaultValue("") String authToken,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("500") int batchSize,
        @DefaultValue("1s") Duration flushInterval,
        @DefaultValue("3s") Duration requestTimeout,
        @DefaultValue("250ms") Duration initialBackoff,
        @DefaultValue("30s") Duration maxBackoff,
        @DefaultValue("5s") Duration shutdownTimeout) {

    public DecisionSinkProperties {
        if (endpoint == null || endpoint.getScheme() == null
                || !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
            throw new IllegalArgumentException("maluca.decision-sink.endpoint must be an HTTP(S) URI");
        }
        if (authHeader == null || authHeader.isBlank()) {
            throw new IllegalArgumentException("maluca.decision-sink.auth-header must not be blank");
        }
        if (enabled && (authToken == null || authToken.isBlank())) {
            throw new IllegalArgumentException(
                    "maluca.decision-sink.auth-token must be set when decision export is enabled");
        }
        requirePositive(queueCapacity, "queue-capacity");
        requirePositive(batchSize, "batch-size");
        if (batchSize > queueCapacity) {
            throw new IllegalArgumentException("maluca.decision-sink.batch-size must not exceed queue-capacity");
        }
        requirePositive(flushInterval, "flush-interval");
        requirePositive(requestTimeout, "request-timeout");
        requirePositive(initialBackoff, "initial-backoff");
        requirePositive(maxBackoff, "max-backoff");
        requirePositive(shutdownTimeout, "shutdown-timeout");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException(
                    "maluca.decision-sink.initial-backoff must not exceed max-backoff");
        }
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("maluca.decision-sink." + property + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("maluca.decision-sink." + property + " must be positive");
        }
    }
}
