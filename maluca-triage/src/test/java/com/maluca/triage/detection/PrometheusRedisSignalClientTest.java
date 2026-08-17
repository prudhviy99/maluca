package com.maluca.triage.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PrometheusRedisSignalClientTest {

    @Test
    void readsTheBoundedRedisCounterIncrease() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://prometheus/api/v1/query")))
                .andExpect(queryParam("query",
                        "sum(increase(maluca_redis_errors_total%5B60s%5D))"))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1786564800,"3.5"]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var signal = new PrometheusRedisSignalClient(builder.build())
                .redisErrorIncrease(Duration.ofSeconds(60));

        assertThat(signal).isPresent().hasValue(3.5);
        server.verify();
    }

    @Test
    void reportsUnknownInsteadOfNormalWhenPrometheusFails() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://prometheus/api/v1/query")))
                .andRespond(withServerError());

        assertThat(new PrometheusRedisSignalClient(builder.build())
                .redisErrorIncrease(Duration.ofSeconds(60))).isEmpty();
        server.verify();
    }
}
