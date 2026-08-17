package com.maluca.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.maluca.mcp.config.MalucaMcpProperties;

public final class TestProperties {

    private TestProperties() {
    }

    public static MalucaMcpProperties defaults() {
        return properties(false, "agent-secret", "human-secret", 1_048_576);
    }

    public static MalucaMcpProperties properties(
            boolean applyEnabled,
            String agentToken,
            String approvalToken,
            int maxResponseBytes) {
        MalucaMcpProperties.Upstream triage = upstream("http://triage.test", maxResponseBytes);
        MalucaMcpProperties.Upstream proxy = upstream("http://proxy.test", maxResponseBytes);
        MalucaMcpProperties.Upstream prometheus = upstream("http://prometheus.test", maxResponseBytes);
        return new MalucaMcpProperties(
                applyEnabled,
                new MalucaMcpProperties.Security(agentToken, approvalToken, "operator@example.test"),
                "triage-operator-secret",
                triage,
                proxy,
                prometheus,
                new MalucaMcpProperties.Limits(
                        100, 200, 8, 12, 2048, 2000, 100, Duration.ofHours(24)),
                new MalucaMcpProperties.Promql(
                        List.of("maluca_", "http_server_", "jvm_", "process_", "system_", "up"),
                        Duration.ofHours(6), Duration.ofSeconds(15), Duration.ofSeconds(5), 5000, 50));
    }

    public static MalucaMcpProperties withPromqlTimeout(Duration timeout) {
        MalucaMcpProperties base = defaults();
        return new MalucaMcpProperties(
                base.applyEnabled(), base.security(), base.triageApprovalToken(),
                base.triage(), base.proxy(), base.prometheus(),
                base.limits(), new MalucaMcpProperties.Promql(
                        base.promql().allowedMetricPrefixes(), base.promql().maxRange(),
                        base.promql().minStep(), timeout,
                        base.promql().maxSamples(), base.promql().maxSeries()));
    }

    private static MalucaMcpProperties.Upstream upstream(String url, int maxResponseBytes) {
        return new MalucaMcpProperties.Upstream(
                URI.create(url), "upstream-secret", Duration.ofSeconds(1),
                Duration.ofSeconds(8), maxResponseBytes);
    }
}
