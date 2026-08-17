package com.maluca.mcp.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "maluca.mcp")
public record MalucaMcpProperties(
        @DefaultValue("false") boolean applyEnabled,
        @Valid @DefaultValue Security security,
        @DefaultValue("") String triageApprovalToken,
        @Valid @NotNull Upstream triage,
        @Valid @NotNull Upstream proxy,
        @Valid @NotNull Upstream prometheus,
        @Valid @DefaultValue Limits limits,
        @Valid @DefaultValue Promql promql) {

    public record Security(
            @DefaultValue("") String bearerToken,
            @DefaultValue("") String approvalBearerToken,
            @DefaultValue("maluca-operator") String approvalPrincipal) {
    }

    public record Upstream(
            @NotNull URI baseUrl,
            @DefaultValue("") String authToken,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("5s") Duration readTimeout,
            @Min(1024) @Max(5_242_880) @DefaultValue("1048576") int maxResponseBytes) {
    }

    public record Limits(
            @Min(1) @Max(1000) @DefaultValue("100") int defaultResultLimit,
            @Min(1) @Max(1000) @DefaultValue("200") int maxResultLimit,
            @Min(1) @Max(100) @DefaultValue("8") int defaultRunbookLimit,
            @Min(1) @Max(12) @DefaultValue("12") int maxRunbookLimit,
            @Min(16) @Max(16_384) @DefaultValue("2048") int maxQueryCharacters,
            @Min(16) @Max(2_000) @DefaultValue("2000") int maxRationaleCharacters,
            @Min(1) @Max(1000) @DefaultValue("100") int maxPatchEntries,
            @NotNull @DefaultValue("24h") Duration maxEvidenceWindow) {

        public Limits {
            if (defaultResultLimit > maxResultLimit) {
                throw new IllegalArgumentException("default-result-limit cannot exceed max-result-limit");
            }
            if (defaultRunbookLimit > maxRunbookLimit) {
                throw new IllegalArgumentException("default-runbook-limit cannot exceed max-runbook-limit");
            }
            if (maxEvidenceWindow == null || maxEvidenceWindow.isZero() || maxEvidenceWindow.isNegative()) {
                throw new IllegalArgumentException("max-evidence-window must be positive");
            }
        }
    }

    public record Promql(
            @DefaultValue({ "maluca_", "http_server_", "jvm_", "process_", "system_", "up" })
            List<String> allowedMetricPrefixes,
            @NotNull @DefaultValue("6h") Duration maxRange,
            @NotNull @DefaultValue("15s") Duration minStep,
            @NotNull @DefaultValue("5s") Duration queryTimeout,
            @Min(1) @Max(100_000) @DefaultValue("5000") int maxSamples,
            @Min(1) @Max(1000) @DefaultValue("50") int maxSeries) {

        public Promql {
            allowedMetricPrefixes = allowedMetricPrefixes == null
                    ? List.of()
                    : List.copyOf(allowedMetricPrefixes);
            if (allowedMetricPrefixes.stream().noneMatch(value -> value != null && !value.isBlank())) {
                throw new IllegalArgumentException("at least one PromQL metric prefix is required");
            }
            if (maxRange == null || maxRange.isZero() || maxRange.isNegative()) {
                throw new IllegalArgumentException("PromQL max-range must be positive");
            }
            if (minStep == null || minStep.isZero() || minStep.isNegative()) {
                throw new IllegalArgumentException("PromQL min-step must be positive");
            }
            if (queryTimeout == null || queryTimeout.compareTo(Duration.ofMillis(1)) < 0
                    || queryTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || queryTimeout.toNanosPart() % 1_000_000 != 0) {
                throw new IllegalArgumentException("PromQL query-timeout must be between 1ms and 30s");
            }
        }
    }
}
