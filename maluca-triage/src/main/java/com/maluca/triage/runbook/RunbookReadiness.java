package com.maluca.triage.runbook;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Gates RAG consumers and exposes whether a trusted corpus is available. */
@Component
public class RunbookReadiness implements HealthIndicator {

    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicReference<String> state = new AtomicReference<>("startup ingestion pending");

    public boolean isReady() {
        return ready.get();
    }

    public void ready(String detail) {
        state.set(detail);
        ready.set(true);
    }

    public void unavailable(String detail) {
        ready.set(false);
        state.set(detail);
    }

    public void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("trusted runbook corpus is not ready: " + state.get());
        }
    }

    @Override
    public Health health() {
        return (isReady() ? Health.up() : Health.outOfService())
                .withDetail("state", state.get())
                .build();
    }
}
