package com.maluca.mcp.client;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.config.MalucaMcpProperties;
import com.maluca.mcp.validation.PromQlPolicy;

@Component
public class PrometheusClient {

    private final BoundedJsonClient http;
    private final PromQlPolicy policy;
    private final Duration queryTimeout;

    public PrometheusClient(
            @Qualifier("prometheusRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            MalucaMcpProperties properties,
            PromQlPolicy policy) {
        this.http = new BoundedJsonClient("prometheus", restClient, objectMapper,
                properties.prometheus().maxResponseBytes());
        this.policy = policy;
        this.queryTimeout = properties.promql().queryTimeout();
    }

    public JsonNode queryRange(String query, Instant start, Instant end, Duration step) {
        policy.validateRequest(query, start, end, step);
        JsonNode response = http.get(uri -> uri.path("/api/v1/query_range")
                .queryParam("query", query)
                .queryParam("start", start.toString())
                .queryParam("end", end.toString())
                .queryParam("step", step.toSeconds())
                .queryParam("timeout", formatDuration(queryTimeout))
                .build());
        policy.validateResponse(response);
        return response;
    }

    private static String formatDuration(Duration duration) {
        if (duration.toMillis() % 1000 == 0) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }
}
