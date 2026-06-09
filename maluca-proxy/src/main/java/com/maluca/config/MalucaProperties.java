package com.maluca.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Infrastructure-level configuration. Behavioral thresholds (scores, bands,
 * limits) start here and migrate into the hot-reloadable policy file in
 * Phase 6; everything in this class requires a restart.
 */
@ConfigurationProperties(prefix = "maluca")
public record MalucaProperties(
        Upstream upstream,
        Identity identity,
        Limits limits,
        Scoring scoring,
        Bands bands,
        Hysteresis hysteresis,
        Mitigation mitigation,
        @DefaultValue("") List<String> sensitivePaths) {

    public record Upstream(
            String url,
            @DefaultValue("5000") int connectTimeoutMs,
            @DefaultValue("30000") long responseTimeoutMs,
            @DefaultValue("500") int maxConnections) {
    }

    public record Identity(
            @DefaultValue("false") boolean trustXForwardedFor,
            @DefaultValue("") List<String> trustedProxies) {
    }

    /** Baseline fixed-window cap. Breaching it feeds the score and floors the action at HARD_LIMIT. */
    public record Limits(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") long maxRequests,
            @DefaultValue("10") long windowSeconds,
            @DefaultValue("300") long blockThresholdPer60s,
            @DefaultValue("5") long blockMinutes) {
    }

    public record Scoring(Weights weights, Thresholds thresholds) {

        public record Weights(
                @DefaultValue("40") int burst,
                @DefaultValue("25") int sustained,
                @DefaultValue("20") int pathScan,
                @DefaultValue("25") int sensitive,
                @DefaultValue("15") int fourxx,
                @DefaultValue("15") int headerAnomaly,
                @DefaultValue("60") int knownBadBot,
                @DefaultValue("15") int scriptClient,
                @DefaultValue("8") int unknownUa,
                @DefaultValue("30") int limitExceeded,
                @DefaultValue("20") int priorEscalation) {
        }

        public record Thresholds(
                @DefaultValue("30") long burstPer10s,
                @DefaultValue("120") long sustainedPer60s,
                @DefaultValue("15") long distinctPathsPer30s,
                @DefaultValue("20") long sensitiveHitsPer60s,
                @DefaultValue("10") long fourxxPer60s) {
        }
    }

    /** Score bands → actions. Half-open intervals, most severe first wins. */
    public record Bands(
            @DefaultValue("30") int observeMin,
            @DefaultValue("50") int softLimitMin,
            @DefaultValue("65") int hardLimitMin,
            @DefaultValue("75") int challengeMin,
            @DefaultValue("90") int blockMin) {
    }

    /** TTLs for pinning escalated clients so they don't flap between bands. */
    public record Hysteresis(
            @DefaultValue("30") long hardLimitTtlSeconds,
            @DefaultValue("120") long challengeTtlSeconds,
            @DefaultValue("300") long blockTtlSeconds) {
    }

    public record Mitigation(
            @DefaultValue("500") long softLimitDelayMs) {
    }
}
