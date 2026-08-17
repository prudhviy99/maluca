package com.maluca.triage.detection;

import java.time.Duration;
import java.util.OptionalDouble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/** Bounded instant-query client for deterministic Redis-error counter deltas. */
@Component
public class PrometheusRedisSignalClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusRedisSignalClient.class);
    private final RestClient prometheus;

    public PrometheusRedisSignalClient(
            @Qualifier("prometheusRestClient") RestClient prometheus) {
        this.prometheus = prometheus;
    }

    public OptionalDouble redisErrorIncrease(Duration window) {
        long seconds = Math.max(1, (window.toMillis() + 999) / 1_000);
        String query = "sum(increase(maluca_redis_errors_total[" + seconds + "s]))";
        try {
            JsonNode response = prometheus.get().uri(uri -> uri.path("/api/v1/query")
                    .queryParam("query", query).build()).retrieve().body(JsonNode.class);
            if (response == null || !"success".equals(response.path("status").asText())) {
                return OptionalDouble.empty();
            }
            JsonNode result = response.path("data").path("result");
            if (result == null || !result.isArray() || result.isEmpty()) {
                return OptionalDouble.of(0);
            }
            JsonNode value = result.get(0).path("value");
            if (!value.isArray() || value.size() != 2) {
                return OptionalDouble.empty();
            }
            double increase = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(increase) && increase >= 0
                    ? OptionalDouble.of(increase) : OptionalDouble.empty();
        } catch (RuntimeException failure) {
            log.warn("prometheus_redis_signal_unavailable reason={}", safeMessage(failure));
            return OptionalDouble.empty();
        }
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
                : message.substring(0, Math.min(300, message.length()));
    }
}
