package com.maluca.web;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.model.ClientIdentity;
import com.maluca.model.Decision;
import com.maluca.model.MitigationAction;
import com.maluca.policy.CompiledPolicy;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** Builds the shared wire event from one completed proxy decision. */
@Component
public final class DecisionEventFactory {

    private final Tracer tracer;
    private final Clock clock;
    private final Supplier<UUID> eventIds;

    @Autowired
    public DecisionEventFactory(Tracer tracer) {
        this(tracer, Clock.systemUTC(), UUID::randomUUID);
    }

    DecisionEventFactory(Tracer tracer, Clock clock, Supplier<UUID> eventIds) {
        this.tracer = tracer;
        this.clock = clock;
        this.eventIds = eventIds;
    }

    public DecisionEvent create(ServerWebExchange exchange,
                                ClientIdentity identity,
                                Decision decision,
                                CompiledPolicy policy,
                                String tier,
                                String path) {
        MitigationAction executed = decision.dryRun() ? MitigationAction.ALLOW : decision.action();
        return new DecisionEvent(
                eventIds.get(),
                Instant.now(clock),
                identity.compositeKey(),
                exchange.getRequest().getMethod().name(),
                path,
                policy != null ? policy.name() : "none",
                policy != null ? policy.pattern().getPatternString() : "none",
                policy != null ? policy.mode().name() : "ENFORCE",
                tier,
                decision.action().name(),
                executed.name(),
                decision.score(),
                decision.reason(),
                decision.contributions(),
                decision.dryRun(),
                currentTraceId());
    }

    private String currentTraceId() {
        try {
            Span span = tracer.currentSpan();
            return span == null ? "" : span.context().traceId();
        } catch (RuntimeException ignored) {
            // Trace export is useful correlation, never a request-path dependency.
            return "";
        }
    }
}
