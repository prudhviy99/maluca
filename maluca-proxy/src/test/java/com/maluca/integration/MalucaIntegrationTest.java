package com.maluca.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-stack integration test: real Redis (Testcontainers) + a WireMock-free
 * stub upstream (the demo backend isn't on the proxy's classpath, so we point
 * upstream at a tiny endpoint the test itself serves via the running app's
 * own actuator — instead we just assert Maluca's synthetic responses and
 * decision behavior, which don't require a live upstream).
 *
 * Skipped automatically when Docker isn't available, so unit runs stay green.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "maluca.limits.max-requests=5",
                "maluca.limits.window-seconds=60",
                "maluca.upstream.url=http://localhost:1",   // unreachable: allowed reqs -> 502, which is fine for these assertions
                "maluca.admin-token=test-token"
        })
@Testcontainers(disabledWithoutDocker = true)
class MalucaIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // RANDOM_PORT auto-configures a WebTestClient already bound to the live
    // server. Give it a generous timeout — the unreachable-upstream path waits
    // on a connection refusal before returning 502.
    @Autowired
    WebTestClient webTestClient;

    private WebTestClient client() {
        return webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void allowedRequestReachesProxyPipeline() {
        // upstream is unreachable, so an allowed request returns Maluca's 502 —
        // proving the request traversed the full pipeline and was forwarded
        client().get().uri("/api/products")
                .header("User-Agent", "Mozilla/5.0 (Chrome) Safari")
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(200, 502));
    }

    @Test
    void floodGetsRateLimitedThenBlocked() {
        WebTestClient c = client();
        int mitigated = 0;
        for (int i = 0; i < 40; i++) {
            int status = c.get().uri("/flood-test")
                    .header("User-Agent", "burst-bot/1.0")
                    .exchange()
                    .returnResult(byte[].class)
                    .getStatus().value();
            if (status == 429 || status == 403) {
                mitigated++;
            }
        }
        assertThat(mitigated).as("limit of 5/60s must mitigate most of 40 requests").isGreaterThan(20);
    }

    @Test
    void adminApiRequiresToken() {
        client().get().uri("/_maluca/admin/policies")
                .exchange().expectStatus().isUnauthorized();

        client().get().uri("/_maluca/admin/policies")
                .header("X-Maluca-Admin-Token", "test-token")
                .exchange().expectStatus().isOk();
    }

    @Test
    void actuatorHealthReportsDegradationDetail() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.components.redisBreaker.details.degradation").isEqualTo("FULL");
    }
}
