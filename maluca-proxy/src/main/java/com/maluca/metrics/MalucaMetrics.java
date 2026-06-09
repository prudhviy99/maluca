package com.maluca.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.maluca.model.MitigationAction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * All Maluca metrics in one place. Labels are bounded enums/route patterns —
 * never client IPs or raw paths — to keep metric cardinality under control.
 */
@Component
public class MalucaMetrics {

    private final MeterRegistry registry;
    private final Map<MitigationAction, Counter> decisionCounters = new EnumMap<>(MitigationAction.class);
    private final Timer upstreamLatency;
    private final Timer addedLatency;
    private final Counter upstreamErrors;
    private final Counter redisErrors;

    public MalucaMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (MitigationAction action : MitigationAction.values()) {
            decisionCounters.put(action, Counter.builder("maluca_decisions_total")
                    .description("Mitigation decisions by action")
                    .tag("action", action.name())
                    .register(registry));
        }
        this.upstreamLatency = Timer.builder("maluca_upstream_latency")
                .description("Time spent waiting on the upstream")
                .publishPercentileHistogram()
                .register(registry);
        this.addedLatency = Timer.builder("maluca_added_latency")
                .description("Latency added by Maluca before the upstream call (identity + state + decision)")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMillis(250))
                .register(registry);
        this.upstreamErrors = Counter.builder("maluca_upstream_errors_total")
                .description("Upstream connect/response failures")
                .register(registry);
        this.redisErrors = Counter.builder("maluca_redis_errors_total")
                .description("Redis failures on the decision path")
                .register(registry);
    }

    public void recordDecision(MitigationAction action) {
        decisionCounters.get(action).increment();
    }

    public void recordDecision(MitigationAction action, String route, String mode) {
        recordDecision(action);
        Counter.builder("maluca_route_decisions_total")
                .tag("action", action.name())
                .tag("route", route)
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    public void recordUpstreamLatency(long nanos) {
        upstreamLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordAddedLatency(long nanos) {
        addedLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void incrementUpstreamErrors() {
        upstreamErrors.increment();
    }

    public void incrementRedisErrors() {
        redisErrors.increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
