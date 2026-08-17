package com.maluca.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.TestProperties;
import com.maluca.mcp.validation.PromQlPolicy;

class PrometheusClientTest {

    @Test
    void sendsOnlyTheFixedRangeEndpointWithServerSideTimeout() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://prometheus.test/api/v1/query_range?query=maluca_decisions_total&start=2026-08-12T10:00:00Z&end=2026-08-12T11:00:00Z&step=30&timeout=5s"))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));
        var properties = TestProperties.defaults();
        PrometheusClient client = new PrometheusClient(
                builder.build(), new ObjectMapper(), properties, new PromQlPolicy(properties));

        var response = client.queryRange(
                "maluca_decisions_total",
                Instant.parse("2026-08-12T10:00:00Z"),
                Instant.parse("2026-08-12T11:00:00Z"),
                Duration.ofSeconds(30));

        assertThat(response.path("status").asText()).isEqualTo("success");
        server.verify();
    }
}
