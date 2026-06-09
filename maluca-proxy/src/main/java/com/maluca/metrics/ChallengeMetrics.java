package com.maluca.metrics;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Challenge funnel: issued → solved | failed(reason). Solve rate is a key tuning input. */
@Component
public class ChallengeMetrics {

    private final MeterRegistry registry;

    public ChallengeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void issued(String type) {
        Counter.builder("maluca_challenges_total")
                .tag("event", "issued").tag("type", type)
                .register(registry).increment();
    }

    public void solved() {
        Counter.builder("maluca_challenges_total")
                .tag("event", "solved").tag("type", "any")
                .register(registry).increment();
    }

    public void failed(String reason) {
        Counter.builder("maluca_challenges_total")
                .tag("event", "failed_" + reason).tag("type", "any")
                .register(registry).increment();
    }

    public void passBypass() {
        Counter.builder("maluca_pass_bypass_total")
                .description("Requests admitted via valid maluca_pass cookie")
                .register(registry).increment();
    }
}
