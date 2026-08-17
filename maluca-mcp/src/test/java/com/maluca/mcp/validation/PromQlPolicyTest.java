package com.maluca.mcp.validation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.TestProperties;

class PromQlPolicyTest {

    private PromQlPolicy policy;
    private final Instant end = Instant.parse("2026-08-12T20:00:00Z");

    @BeforeEach
    void setUp() {
        policy = new PromQlPolicy(TestProperties.defaults());
    }

    @Test
    void acceptsAllowedMetricsAndConservativeFunctions() {
        policy.validateRequest(
                "sum by (action) (rate(maluca_decisions_total{action=\"BLOCK\"}[5m]))",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30));
    }

    @Test
    void rejectsMetricsOutsideTheAllowlist() {
        assertThatThrownBy(() -> policy.validateRequest(
                "rate(node_cpu_seconds_total[5m])",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("outside the allowed namespaces");

        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total or {__name__=\"node_cpu_seconds_total\"}",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("__name__");

        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total or {job!=\"\"}",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("explicit allowed metric name");

        assertThatThrownBy(() -> policy.validateRequest(
                "topk(1000000000, maluca_decisions_total)",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("between 1 and 50");
    }

    @Test
    void rejectsTimeShiftingAndUnboundedRanges() {
        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total offset 1d",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("offset");

        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total",
                end.minus(Duration.ofHours(7)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("range exceeds");

        assertThatThrownBy(() -> policy.validateRequest(
                "rate(maluca_decisions_total[1d])",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("range selector exceeds");

        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total[5m:1s]",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("subqueries");

        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total * on(client) group_left http_server_requests_seconds_count",
                end.minus(Duration.ofHours(1)), end, Duration.ofSeconds(30)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("vector joins");
    }

    @Test
    void rejectsRequestsAndResponsesThatExceedSampleCaps() throws Exception {
        assertThatThrownBy(() -> policy.validateRequest(
                "maluca_decisions_total",
                end.minus(Duration.ofHours(6)), end, Duration.ofSeconds(1)))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("at least PT15S");

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append("[0,\"1\"]");
        }
        var response = new ObjectMapper().readTree(
                "{\"status\":\"success\",\"data\":{\"result\":[{\"values\":[" + values + "]}]}}");
        assertThatThrownBy(() -> policy.validateResponse(response))
                .isInstanceOf(PromQlPolicy.UpstreamResultException.class)
                .hasMessageContaining("5000 samples");
    }
}
