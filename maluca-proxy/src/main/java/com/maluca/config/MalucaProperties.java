package com.maluca.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Infrastructure-level configuration. Behavioral thresholds (scores, bands,
 * limits) live in the policy file so they can be hot-reloaded; everything
 * here requires a restart.
 */
@ConfigurationProperties(prefix = "maluca")
public record MalucaProperties(
        Upstream upstream,
        Identity identity,
        Limits limits,
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

    /** MVP fixed-window limits; superseded by per-route policies in later phases. */
    public record Limits(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") long maxRequests,
            @DefaultValue("10") long windowSeconds,
            @DefaultValue("300") long blockThresholdPer60s,
            @DefaultValue("5") long blockMinutes) {
    }
}
