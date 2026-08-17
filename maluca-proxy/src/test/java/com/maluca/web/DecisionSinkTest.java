package com.maluca.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.maluca.config.DecisionSinkProperties;
import com.maluca.contracts.decision.DecisionBatch;
import com.maluca.contracts.decision.DecisionEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DecisionSinkTest {

    private static final String TOKEN_HEADER = "X-Maluca-Internal-Token";

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<byte[]> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;
    private DecisionSink sink;
    private volatile IntUnaryOperator statusForAttempt = ignored -> 204;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/decisions", this::handle);
        server.start();
    }

    @AfterEach
    void stopResources() {
        if (sink != null) {
            sink.close();
        }
        server.stop(0);
        metrics.close();
    }

    @Test
    void disabledSinkIsANoOp() throws Exception {
        sink = new DecisionSink(properties(false, 3, 2, Duration.ofMillis(20)), mapper, metrics);
        sink.start();

        sink.offer(event(1));
        Thread.sleep(75);

        assertThat(requests).hasValue(0);
        assertThat(gauge("maluca.sink.queue.size")).isZero();
        assertThat(counter("maluca.sink.dropped")).isZero();
        assertThat(counter("maluca.sink.permanent.dropped")).isZero();
    }

    @Test
    void batchThresholdTriggersAuthenticatedDelivery() throws Exception {
        sink = new DecisionSink(properties(true, 10, 3, Duration.ofSeconds(5)), mapper, metrics);
        sink.start();

        sink.offer(event(1));
        sink.offer(event(2));
        sink.offer(event(3));

        await(() -> requests.get() == 1 && counter("maluca.sink.success") == 3,
                Duration.ofSeconds(3));
        DecisionBatch batch = mapper.readValue(requestBodies.getFirst(), DecisionBatch.class);
        assertThat(batch.events()).extracting(DecisionEvent::eventId)
                .containsExactly(id(1), id(2), id(3));
        assertThat(authHeaders).containsExactly("test-internal-token");
        assertThat(counter("maluca.sink.success")).isEqualTo(3);
        assertThat(counter("maluca.sink.failure")).isZero();
    }

    @Test
    void overflowDropsOldestQueuedEvent() throws Exception {
        sink = new DecisionSink(properties(true, 3, 3, Duration.ofMillis(20)), mapper, metrics);

        // Fill before starting the consumer so the replacement is deterministic.
        sink.offer(event(1));
        sink.offer(event(2));
        sink.offer(event(3));
        sink.offer(event(4));
        assertThat(gauge("maluca.sink.queue.size")).isEqualTo(3);
        assertThat(counter("maluca.sink.dropped")).isEqualTo(1);

        sink.start();
        await(() -> requests.get() == 1 && counter("maluca.sink.success") == 3,
                Duration.ofSeconds(3));

        DecisionBatch batch = mapper.readValue(requestBodies.getFirst(), DecisionBatch.class);
        assertThat(batch.events()).extracting(DecisionEvent::eventId)
                .containsExactly(id(2), id(3), id(4));
    }

    @Test
    void failedBatchIsRetriedAndRecoversWithoutLosingIt() throws Exception {
        statusForAttempt = attempt -> attempt == 1 ? 503 : 204;
        sink = new DecisionSink(properties(true, 10, 2, Duration.ofMillis(20)), mapper, metrics);
        sink.start();

        sink.offer(event(1));
        sink.offer(event(2));

        await(() -> requests.get() >= 2 && counter("maluca.sink.success") == 2,
                Duration.ofSeconds(3));

        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).isEqualTo(requestBodies.get(1));
        assertThat(counter("maluca.sink.failure")).isEqualTo(1);
        assertThat(counter("maluca.sink.success")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void retryableClientResponseKeepsTheBatch(int retryableStatus) throws Exception {
        statusForAttempt = attempt -> attempt == 1 ? retryableStatus : 204;
        sink = new DecisionSink(properties(true, 10, 2, Duration.ofMillis(20)), mapper, metrics);
        sink.start();

        sink.offer(event(1));
        sink.offer(event(2));

        await(() -> requests.get() >= 2 && counter("maluca.sink.success") == 2,
                Duration.ofSeconds(3));

        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(0)).isEqualTo(requestBodies.get(1));
        assertThat(counter("maluca.sink.failure")).isEqualTo(1);
        assertThat(counter("maluca.sink.permanent.dropped")).isZero();
    }

    @Test
    void permanentClientResponseDropsBatchAndAllowsLaterEvidenceToProgress() throws Exception {
        statusForAttempt = attempt -> attempt == 1 ? 400 : 204;
        sink = new DecisionSink(properties(true, 10, 2, Duration.ofMillis(20)), mapper, metrics);
        sink.start();

        sink.offer(event(1));
        sink.offer(event(2));

        await(() -> requests.get() == 1
                        && counter("maluca.sink.permanent.dropped") == 2,
                Duration.ofSeconds(3));
        sink.offer(event(3));
        sink.offer(event(4));

        await(() -> requests.get() == 2 && counter("maluca.sink.success") == 2,
                Duration.ofSeconds(3));

        assertThat(requestBodies).hasSize(2);
        DecisionBatch rejected = mapper.readValue(requestBodies.get(0), DecisionBatch.class);
        DecisionBatch delivered = mapper.readValue(requestBodies.get(1), DecisionBatch.class);
        assertThat(rejected.events()).extracting(DecisionEvent::eventId)
                .containsExactly(id(1), id(2));
        assertThat(delivered.events()).extracting(DecisionEvent::eventId)
                .containsExactly(id(3), id(4));
        assertThat(counter("maluca.sink.failure")).isEqualTo(1);
        assertThat(counter("maluca.sink.permanent.dropped")).isEqualTo(2);
        assertThat(counter("maluca.sink.dropped")).isZero();
    }

    @Test
    void gracefulShutdownFlushesAPartialBatch() throws Exception {
        sink = new DecisionSink(properties(true, 10, 5, Duration.ofSeconds(5)), mapper, metrics);
        sink.start();
        sink.offer(event(1));

        sink.close();

        assertThat(requests).hasValue(1);
        DecisionBatch batch = mapper.readValue(requestBodies.getFirst(), DecisionBatch.class);
        assertThat(batch.events()).extracting(DecisionEvent::eventId).containsExactly(id(1));
        assertThat(counter("maluca.sink.success")).isEqualTo(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int attempt = requests.incrementAndGet();
        requestBodies.add(exchange.getRequestBody().readAllBytes());
        authHeaders.add(exchange.getRequestHeaders().getFirst(TOKEN_HEADER));
        int status = statusForAttempt.applyAsInt(attempt);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private DecisionSinkProperties properties(boolean enabled, int capacity, int batchSize,
                                              Duration flushInterval) {
        return new DecisionSinkProperties(
                enabled,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/internal/v1/decisions"),
                TOKEN_HEADER,
                enabled ? "test-internal-token" : "",
                capacity,
                batchSize,
                flushInterval,
                Duration.ofSeconds(1),
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
    }

    private DecisionEvent event(int number) {
        return new DecisionEvent(
                id(number),
                Instant.parse("2026-08-12T12:00:00Z").plusSeconds(number),
                "client-" + number,
                "GET",
                "/api/items/" + number,
                "api",
                "/api/**",
                "ENFORCE",
                "anonymous",
                "ALLOW",
                "ALLOW",
                number,
                "score_band",
                Map.of("burst", 1.0),
                false,
                "trace-" + number);
    }

    private static UUID id(int number) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(number));
    }

    private double gauge(String name) {
        return metrics.get(name).gauge().value();
    }

    private double counter(String name) {
        return metrics.get(name).counter().count();
    }

    private static void await(CheckedBooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("condition before %s", timeout).isTrue();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
