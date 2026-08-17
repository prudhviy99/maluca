package com.maluca.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.util.pattern.PathPatternParser;

import com.maluca.TestFixtures;
import com.maluca.config.MalucaProperties.Identity.KeyStrategy;
import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.model.ClientIdentity;
import com.maluca.model.Decision;
import com.maluca.model.MitigationAction;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;
import com.maluca.policy.CompiledPolicy;
import com.maluca.policy.PolicyDefinition.FailMode;
import com.maluca.policy.PolicyDefinition.Mode;
import com.maluca.util.CidrSet;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

class DecisionEventFactoryTest {

    @Test
    void capturesStableIdentityPolicyRequestActionAndTraceFields() {
        Instant occurredAt = Instant.parse("2026-08-12T19:20:21Z");
        UUID eventId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("abcd1234");

        DecisionEventFactory factory = new DecisionEventFactory(
                tracer, Clock.fixed(occurredAt, ZoneOffset.UTC), () -> eventId);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest
                        .post("/api/checkout?coupon=summer").build());
        ClientIdentity identity = ClientIdentity.ofIp("192.0.2.10")
                .withKeys("session-a", "fingerprint-b", KeyStrategy.COMPOSITE);
        Decision decision = Decision.of(MitigationAction.BLOCK, 94, "score_band",
                Map.of("burst", 40.0, "knownBadBot", 60.0)).asDryRun();
        CompiledPolicy policy = new CompiledPolicy(
                "checkout",
                new PathPatternParser().parse("/api/**"),
                Set.of("pro"),
                Mode.DRY_RUN,
                KeyStrategy.COMPOSITE,
                RateLimitConfig.windowed(RateLimitAlgorithm.SLIDING_WINDOW_COUNTER, 60, 10),
                TestFixtures.defaultProperties().bands(),
                CidrSet.EMPTY,
                CidrSet.EMPTY,
                FailMode.FAIL_OPEN);

        DecisionEvent event = factory.create(
                exchange, identity, decision, policy, "pro", "/api/checkout");

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.clientKey()).isEqualTo("192.0.2.10|session-a|fingerprint-b");
        assertThat(event.method()).isEqualTo("POST");
        assertThat(event.path()).isEqualTo("/api/checkout");
        assertThat(event.policyName()).isEqualTo("checkout");
        assertThat(event.policyRoute()).isEqualTo("/api/**");
        assertThat(event.policyMode()).isEqualTo("DRY_RUN");
        assertThat(event.tier()).isEqualTo("pro");
        assertThat(event.computedAction()).isEqualTo("BLOCK");
        assertThat(event.executedAction()).isEqualTo("ALLOW");
        assertThat(event.score()).isEqualTo(94);
        assertThat(event.reason()).isEqualTo("score_band");
        assertThat(event.contributions()).containsEntry("burst", 40.0);
        assertThat(event.dryRun()).isTrue();
        assertThat(event.traceId()).isEqualTo("abcd1234");
    }

    @Test
    void passCookieBypassShapeIsAnExecutedAllowEvent() {
        Tracer tracer = mock(Tracer.class);
        DecisionEventFactory factory = new DecisionEventFactory(
                tracer,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000001"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/store").build());

        DecisionEvent event = factory.create(
                exchange,
                ClientIdentity.ofIp("198.51.100.5"),
                Decision.of(MitigationAction.ALLOW, 0, "pass_cookie_bypass", Map.of()),
                null,
                "anonymous",
                "/store");

        assertThat(event.computedAction()).isEqualTo("ALLOW");
        assertThat(event.executedAction()).isEqualTo("ALLOW");
        assertThat(event.reason()).isEqualTo("pass_cookie_bypass");
        assertThat(event.policyName()).isEqualTo("none");
        assertThat(event.policyRoute()).isEqualTo("none");
        assertThat(event.traceId()).isEmpty();
    }
}
