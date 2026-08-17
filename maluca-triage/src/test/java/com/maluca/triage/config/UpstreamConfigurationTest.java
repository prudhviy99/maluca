package com.maluca.triage.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.maluca.triage.TriageTestFixtures;
import com.sun.net.httpserver.HttpServer;

class UpstreamConfigurationTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void proxyTimeoutsAreStartupValidatedWithinFiniteBounds() {
        assertThatThrownBy(() -> upstreams(Duration.ofMillis(99), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy-connect-timeout");
        assertThatThrownBy(() -> upstreams(Duration.ofSeconds(31), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy-connect-timeout");
        assertThatThrownBy(() -> upstreams(Duration.ofSeconds(2), Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy-read-timeout");
        assertThatThrownBy(() -> upstreams(Duration.ofSeconds(2), Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy-read-timeout");
        assertThatThrownBy(() -> upstreams(null, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxy-connect-timeout");
        assertThatCode(() -> upstreams(Duration.ofMillis(100), Duration.ofSeconds(60)))
                .doesNotThrowAnyException();
    }

    @Test
    void applicationEnvironmentAliasesBindProxyTimeoutOverrides() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "TRIAGE_PROXY_CONNECT_TIMEOUT=3s",
                        "TRIAGE_PROXY_READ_TIMEOUT=7s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TriageProperties.Upstreams upstreams = context
                            .getBean(TriageProperties.class).upstreams();
                    assertThat(upstreams.proxyConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(upstreams.proxyReadTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void proxyClientTimesOutAStalledAdminResponseAndStillSendsAdminToken() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        AtomicReference<String> adminToken = new AtomicReference<>();
        startSlowServer(requestArrived, adminToken);

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        TriageProperties properties = properties(baseUrl,
                Duration.ofSeconds(1), Duration.ofMillis(100));
        RestClient client = new UpstreamConfiguration()
                .malucaProxyClient(RestClient.builder(), properties);

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/_maluca/admin/policies")
                .retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(HttpTimeoutException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(requestArrived.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(adminToken.get()).isEqualTo("admin-token");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    private void startSlowServer(
            CountDownLatch requestArrived, AtomicReference<String> adminToken) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "slow-proxy-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/_maluca/admin/policies", exchange -> {
            adminToken.set(exchange.getRequestHeaders().getFirst("X-Maluca-Admin-Token"));
            requestArrived.countDown();
            try {
                Thread.sleep(2_000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static TriageProperties properties(
            String proxyBaseUrl, Duration connectTimeout, Duration readTimeout) {
        TriageProperties base = TriageTestFixtures.properties(Path.of("policies.yml"));
        return new TriageProperties(base.security(), base.privacy(), base.ingest(),
                base.detection(), base.agent(), base.retrieval(), base.retention(),
                base.policy(), new TriageProperties.Upstreams(
                        proxyBaseUrl, "admin-token", connectTimeout, readTimeout,
                        base.upstreams().prometheusBaseUrl()));
    }

    private static TriageProperties.Upstreams upstreams(
            Duration connectTimeout, Duration readTimeout) {
        return new TriageProperties.Upstreams(
                "http://proxy", "admin", connectTimeout, readTimeout,
                "http://prometheus");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TriageProperties.class)
    static class PropertiesConfiguration {
    }
}
