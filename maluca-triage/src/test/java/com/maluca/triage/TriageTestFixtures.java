package com.maluca.triage;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.maluca.contracts.incident.IncidentStats;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.config.TriageProperties;

public final class TriageTestFixtures {

    private TriageTestFixtures() {
    }

    public static TriageProperties properties(Path policyFile) {
        return new TriageProperties(
                new TriageProperties.Security("api", "internal", "mcp"),
                new TriageProperties.Privacy(true, "test-hmac-key-0123456789", 64),
                new TriageProperties.Ingest(10),
                new TriageProperties.Detection(true, Duration.ofMinutes(1), Duration.ofMinutes(15),
                        Duration.ofSeconds(15), Duration.ofMinutes(5), 30, .25, 3,
                        20, 100, 4, 3, 50, 10),
                new TriageProperties.Agent(true, Duration.ofSeconds(10), Duration.ofSeconds(30),
                        Duration.ofMinutes(2), Duration.ofMinutes(5), 4, 3,
                        Duration.ofSeconds(5), Duration.ofMinutes(1),
                        16_000, 1_200, 8, "test-model", "v4", 1, 150, 12,
                        List.of("get_incidents", "search_runbooks")),
                new TriageProperties.Retrieval(
                        6, .1, "classpath*:runbooks/*.md", false, 8, "test-embedding-model"),
                new TriageProperties.Retention(Duration.ofDays(7), "0 17 * * * *"),
                new TriageProperties.Policy(policyFile, 30, 50, 65, 75, 90, 3, true),
                new TriageProperties.Upstreams("http://localhost", "admin",
                        Duration.ofSeconds(2), Duration.ofSeconds(5), "http://prometheus"));
    }

    public static IncidentView incident() {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        IncidentStats stats = new IncidentStats(
                now.minusSeconds(60), now, 120, 80, .6667, .05, 78, 99,
                12, 3, Map.of("BLOCK", 50L, "CHALLENGE", 30L),
                Map.of("burst_10s", 2400.0), List.of(), List.of());
        return new IncidentView(UUID.fromString("00000000-0000-0000-0000-000000000123"),
                now, null, "api", "/api/**", IncidentTrigger.MITIGATION_SPIKE,
                IncidentStatus.TRIAGED, stats, 3, null, 0, null, null);
    }
}
