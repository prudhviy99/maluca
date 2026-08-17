package com.maluca.web;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.config.DecisionSinkProperties;
import com.maluca.contracts.decision.DecisionBatch;
import com.maluca.contracts.decision.DecisionEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * A bounded, lossy, asynchronous HTTP channel from the proxy hot path to the
 * triage control plane.
 *
 * <p>{@link #offer(DecisionEvent)} performs no I/O, never waits for queue
 * capacity, and never exposes delivery failure to a request. A single
 * dedicated virtual thread owns serialization and HTTP delivery. A failed
 * batch remains in-flight and is retried with capped exponential backoff while
 * producers continue to use the bounded queue, unless triage returns a
 * permanent 4xx rejection. Permanently rejected batches are discarded so one
 * bad batch cannot block all later evidence. On overflow the oldest queued
 * event is discarded in favor of the newest operational evidence.
 */
@Component
public final class DecisionSink implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DecisionSink.class);

    private final DecisionSinkProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LinkedBlockingDeque<DecisionEvent> queue;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean accepting;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Counter dropped;
    private final Counter permanentlyDropped;
    private final Counter success;
    private final Counter failure;

    private volatile Thread worker;
    private volatile boolean backingOff;
    private volatile long shutdownDeadlineNanos = Long.MAX_VALUE;

    @Autowired
    public DecisionSink(DecisionSinkProperties properties,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.queue = new LinkedBlockingDeque<>(properties.queueCapacity());
        this.accepting = new AtomicBoolean(properties.enabled());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();

        Gauge.builder("maluca.sink.queue.size", queue, LinkedBlockingDeque::size)
                .description("Mitigation decision events waiting for triage delivery")
                .strongReference(true)
                .register(meterRegistry);
        this.dropped = Counter.builder("maluca.sink.dropped")
                .description("Mitigation decision events discarded by the bounded sink")
                .register(meterRegistry);
        this.permanentlyDropped = Counter.builder("maluca.sink.permanent.dropped")
                .description("Mitigation decision events discarded after a permanent HTTP rejection")
                .register(meterRegistry);
        this.success = Counter.builder("maluca.sink.success")
                .description("Mitigation decision events delivered to triage")
                .register(meterRegistry);
        this.failure = Counter.builder("maluca.sink.failure")
                .description("Failed decision batch delivery attempts")
                .register(meterRegistry);
    }

    /** Starts the off-hot-path drainer. A disabled sink remains a no-op. */
    @PostConstruct
    public void start() {
        if (!properties.enabled() || !started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        worker = Thread.ofVirtual()
                .name("maluca-decision-sink")
                .unstarted(this::drainLoop);
        worker.start();
        log.info("decision_sink_started endpoint={} capacity={} batchSize={}",
                properties.endpoint(), properties.queueCapacity(), properties.batchSize());
    }

    /** Whether the sink currently accepts events; lets callers skip event allocation when disabled. */
    public boolean isEnabled() {
        return accepting.get();
    }

    /**
     * Enqueues an event without performing I/O or waiting for free capacity.
     * The deque operations are immediate variants: producers never wait on a
     * capacity condition or on the consumer. Concurrent producers may race
     * for a newly freed slot, so a losing producer evicts the then-oldest item
     * and tries again.
     */
    public void offer(DecisionEvent event) {
        if (event == null || !accepting.get()) {
            return;
        }

        while (!queue.offerLast(event)) {
            DecisionEvent discarded = queue.pollFirst();
            if (discarded != null) {
                dropped.increment();
            } else {
                Thread.onSpinWait();
            }
        }

        Thread currentWorker = worker;
        if (currentWorker != null && !backingOff && queue.size() >= properties.batchSize()) {
            LockSupport.unpark(currentWorker);
        }
    }

    private void drainLoop() {
        List<DecisionEvent> pending = null;
        Duration backoff = properties.initialBackoff();
        try {
            while (shouldContinue(pending)) {
                if (pending == null) {
                    if (running.get() && queue.size() < properties.batchSize()) {
                        LockSupport.parkNanos(properties.flushInterval().toNanos());
                    }
                    if (!running.get() && shutdownExpired()) {
                        break;
                    }
                    pending = drainBatch();
                    if (pending.isEmpty()) {
                        pending = null;
                        continue;
                    }
                }

                try {
                    deliver(pending);
                    success.increment(pending.size());
                    pending = null;
                    backoff = properties.initialBackoff();
                } catch (PermanentDeliveryException e) {
                    failure.increment();
                    permanentlyDropped.increment(pending.size());
                    log.warn("decision_sink_delivery_permanently_rejected batchSize={} status={}",
                            pending.size(), e.statusCode());
                    pending = null;
                    backoff = properties.initialBackoff();
                } catch (InterruptedException e) {
                    failure.increment();
                    Thread.currentThread().interrupt();
                    log.warn("decision_sink_delivery_interrupted batchSize={}", pending.size());
                    if (!running.get()) {
                        break;
                    }
                } catch (Exception e) {
                    failure.increment();
                    log.warn("decision_sink_delivery_failed batchSize={} retryInMs={} error={}",
                            pending.size(), backoff.toMillis(), e.toString());
                    backingOff = true;
                    waitForBackoff(backoff);
                    backingOff = false;
                    backoff = doubledAndCapped(backoff);
                }
            }
        } finally {
            backingOff = false;
            long abandoned = pending == null ? 0 : pending.size();
            DecisionEvent event;
            while ((event = queue.pollFirst()) != null) {
                abandoned++;
            }
            if (abandoned > 0) {
                dropped.increment(abandoned);
                log.warn("decision_sink_shutdown_dropped count={}", abandoned);
            }
            running.set(false);
        }
    }

    private List<DecisionEvent> drainBatch() {
        List<DecisionEvent> batch = new ArrayList<>(properties.batchSize());
        queue.drainTo(batch, properties.batchSize());
        return batch;
    }

    private void deliver(List<DecisionEvent> events) throws IOException, InterruptedException {
        byte[] body = objectMapper.writeValueAsBytes(new DecisionBatch(events));
        HttpRequest request = HttpRequest.newBuilder(properties.endpoint())
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(properties.authHeader(), properties.authToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        int statusCode = response.statusCode();
        if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429) {
            throw new PermanentDeliveryException(statusCode);
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("triage ingest returned HTTP " + statusCode);
        }
    }

    private static final class PermanentDeliveryException extends IOException {

        private final int statusCode;

        private PermanentDeliveryException(int statusCode) {
            super("triage ingest permanently rejected batch with HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private boolean shouldContinue(List<DecisionEvent> pending) {
        if (running.get()) {
            return true;
        }
        return !shutdownExpired() && (pending != null || !queue.isEmpty());
    }

    private boolean shutdownExpired() {
        return System.nanoTime() - shutdownDeadlineNanos >= 0;
    }

    private void waitForBackoff(Duration duration) {
        long waitDeadline = deadlineAfter(duration);
        if (!running.get() && shutdownDeadlineNanos - waitDeadline < 0) {
            waitDeadline = shutdownDeadlineNanos;
        }
        while (waitDeadline - System.nanoTime() > 0) {
            LockSupport.parkNanos(waitDeadline - System.nanoTime());
            if (Thread.interrupted() && !running.get()) {
                return;
            }
        }
    }

    private Duration doubledAndCapped(Duration current) {
        Duration doubled;
        try {
            doubled = current.multipliedBy(2);
        } catch (ArithmeticException e) {
            doubled = properties.maxBackoff();
        }
        return doubled.compareTo(properties.maxBackoff()) > 0
                ? properties.maxBackoff()
                : doubled;
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        long deadline = now + nanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    /** Stops admission, then gives the worker a bounded opportunity to flush. */
    @Override
    @PreDestroy
    public void close() {
        accepting.set(false);
        if (!started.get() || !running.getAndSet(false)) {
            return;
        }

        shutdownDeadlineNanos = deadlineAfter(properties.shutdownTimeout());
        Thread currentWorker = worker;
        LockSupport.unpark(currentWorker);
        try {
            long waitMillis = Math.max(1, properties.shutdownTimeout().toMillis());
            currentWorker.join(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (currentWorker.isAlive()) {
            currentWorker.interrupt();
            log.warn("decision_sink_shutdown_timed_out queued={}", queue.size());
        } else {
            log.info("decision_sink_stopped");
        }
    }
}
