package com.maluca.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import com.maluca.TestFixtures;
import com.maluca.challenge.ChallengeService;
import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.identity.ClientIdentityExtractor;
import com.maluca.identity.DatacenterDetector;
import com.maluca.identity.UaClassifier;
import com.maluca.identity.VerifiedBotService;
import com.maluca.metrics.ChallengeMetrics;
import com.maluca.metrics.MalucaMetrics;
import com.maluca.mitigation.HysteresisService;
import com.maluca.mitigation.MitigationExecutor;
import com.maluca.model.ClientIdentity;
import com.maluca.model.Decision;
import com.maluca.model.MitigationAction;
import com.maluca.model.RequestMeta;
import com.maluca.policy.CompiledPolicy;
import com.maluca.policy.PolicyDefinition;
import com.maluca.policy.ClientTierService;
import com.maluca.policy.PolicyRegistry;
import com.maluca.ratelimit.RateLimiter;
import com.maluca.scoring.Scorer;
import com.maluca.scoring.SignalsCollector;
import com.maluca.state.ClientStateRepository;
import com.maluca.state.RedisCircuitBreaker;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import reactor.core.publisher.Mono;
import org.springframework.web.util.pattern.PathPatternParser;

class MitigationWebFilterDecisionExportTest {

    @Test
    void validPassCookieForwardsThroughFinishAndExportsExplicitAllow() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/store")
                .cookie(new HttpCookie("maluca_pass", "signed-pass"))
                .build());
        ClientIdentity identity = ClientIdentity.ofIp("192.0.2.25");
        var identityExtractor = mock(ClientIdentityExtractor.class);
        var tierService = mock(ClientTierService.class);
        var policyRegistry = mock(PolicyRegistry.class);
        var challengeService = mock(ChallengeService.class);
        var executor = mock(MitigationExecutor.class);
        var decisionSink = mock(DecisionSink.class);
        var chain = mock(WebFilterChain.class);

        when(tierService.tierOf(exchange.getRequest())).thenReturn("anonymous");
        when(policyRegistry.resolve("/store", "anonymous")).thenReturn(null);
        when(identityExtractor.extract(eq(exchange), any(RequestMeta.class), isNull()))
                .thenReturn(identity);
        when(challengeService.isValidPass("signed-pass", identity.compositeKey())).thenReturn(true);
        when(executor.execute(eq(exchange), eq(identity), any())).thenReturn(Mono.empty());
        when(decisionSink.isEnabled()).thenReturn(true);

        DecisionEventFactory eventFactory = new DecisionEventFactory(
                mock(Tracer.class),
                Clock.fixed(Instant.parse("2026-08-12T19:20:21Z"), ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000025"));
        MitigationWebFilter filter = new MitigationWebFilter(
                identityExtractor,
                mock(UaClassifier.class),
                mock(VerifiedBotService.class),
                mock(DatacenterDetector.class),
                tierService,
                policyRegistry,
                mock(ClientStateRepository.class),
                mock(RedisCircuitBreaker.class),
                mock(RateLimiter.class),
                mock(SignalsCollector.class),
                mock(Scorer.class),
                mock(HysteresisService.class),
                executor,
                challengeService,
                mock(ChallengeMetrics.class),
                mock(MalucaMetrics.class),
                mock(DecisionLogger.class),
                eventFactory,
                decisionSink,
                TestFixtures.defaultProperties(),
                ObservationRegistry.NOOP);

        filter.filter(exchange, chain).block();

        ArgumentCaptor<DecisionEvent> event = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(decisionSink).offer(event.capture());
        assertThat(event.getValue().computedAction()).isEqualTo("ALLOW");
        assertThat(event.getValue().executedAction()).isEqualTo("ALLOW");
        assertThat(event.getValue().reason()).isEqualTo("pass_cookie_bypass");
        assertThat(event.getValue().tier()).isEqualTo("anonymous");
        verify(executor).execute(eq(exchange), eq(identity), any());
        verify(chain, never()).filter(exchange);
    }

    @Test
    void observeDenylistRecordsBlockButExecutesAllow() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/store").build());
        ClientIdentity identity = ClientIdentity.ofIp("192.0.2.25");
        var policy = policy(PolicyDefinition.Mode.OBSERVE, PolicyDefinition.FailMode.FAIL_OPEN,
                java.util.List.of("192.0.2.25"));
        Harness harness = harness(exchange, identity, policy, true);

        harness.filter.filter(exchange, harness.chain).block();

        ArgumentCaptor<Decision> executed = ArgumentCaptor.forClass(Decision.class);
        verify(harness.executor).execute(eq(exchange), eq(identity), executed.capture());
        assertThat(executed.getValue().action()).isEqualTo(MitigationAction.BLOCK);
        assertThat(executed.getValue().dryRun()).isTrue();
        ArgumentCaptor<DecisionEvent> event = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(harness.sink).offer(event.capture());
        assertThat(event.getValue().computedAction()).isEqualTo("BLOCK");
        assertThat(event.getValue().executedAction()).isEqualTo("ALLOW");
    }

    @Test
    void dryRunFailClosedRedisRecordsBlockButExecutesAllow() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/login").build());
        ClientIdentity identity = ClientIdentity.ofIp("192.0.2.26");
        var policy = policy(PolicyDefinition.Mode.DRY_RUN, PolicyDefinition.FailMode.FAIL_CLOSED,
                java.util.List.of());
        Harness harness = harness(exchange, identity, policy, false);

        harness.filter.filter(exchange, harness.chain).block();

        ArgumentCaptor<Decision> executed = ArgumentCaptor.forClass(Decision.class);
        verify(harness.executor).execute(eq(exchange), eq(identity), executed.capture());
        assertThat(executed.getValue().reason()).isEqualTo("redis_down_fail_closed");
        assertThat(executed.getValue().action()).isEqualTo(MitigationAction.BLOCK);
        assertThat(executed.getValue().dryRun()).isTrue();
        ArgumentCaptor<DecisionEvent> event = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(harness.sink).offer(event.capture());
        assertThat(event.getValue().computedAction()).isEqualTo("BLOCK");
        assertThat(event.getValue().executedAction()).isEqualTo("ALLOW");
    }

    private static Harness harness(MockServerWebExchange exchange, ClientIdentity identity,
                                   CompiledPolicy policy, boolean redisHealthy) {
        var identityExtractor = mock(ClientIdentityExtractor.class);
        var tierService = mock(ClientTierService.class);
        var policyRegistry = mock(PolicyRegistry.class);
        var stateRepository = mock(ClientStateRepository.class);
        var executor = mock(MitigationExecutor.class);
        var sink = mock(DecisionSink.class);
        var chain = mock(WebFilterChain.class);
        String path = exchange.getRequest().getURI().getRawPath();
        when(tierService.tierOf(exchange.getRequest())).thenReturn("anonymous");
        when(policyRegistry.resolve(path, "anonymous")).thenReturn(policy);
        when(identityExtractor.extract(eq(exchange), any(RequestMeta.class), eq(policy.keying())))
                .thenReturn(identity);
        when(stateRepository.redisHealthy()).thenReturn(redisHealthy);
        when(executor.execute(eq(exchange), eq(identity), any())).thenReturn(Mono.empty());
        when(sink.isEnabled()).thenReturn(true);
        var eventFactory = new DecisionEventFactory(mock(Tracer.class));
        var filter = new MitigationWebFilter(
                identityExtractor, mock(UaClassifier.class), mock(VerifiedBotService.class),
                mock(DatacenterDetector.class), tierService, policyRegistry, stateRepository,
                mock(RedisCircuitBreaker.class), mock(RateLimiter.class), mock(SignalsCollector.class),
                mock(Scorer.class), mock(HysteresisService.class), executor,
                mock(ChallengeService.class), mock(ChallengeMetrics.class), mock(MalucaMetrics.class),
                mock(DecisionLogger.class), eventFactory, sink, TestFixtures.defaultProperties(),
                ObservationRegistry.NOOP);
        return new Harness(filter, executor, sink, chain);
    }

    private static CompiledPolicy policy(PolicyDefinition.Mode mode,
                                         PolicyDefinition.FailMode failMode,
                                         java.util.List<String> denylist) {
        return new CompiledPolicy("test", new PathPatternParser().parse("/**"), java.util.Set.of(),
                mode, null, null, null, com.maluca.util.CidrSet.EMPTY,
                com.maluca.util.CidrSet.of(denylist), failMode);
    }

    private record Harness(MitigationWebFilter filter, MitigationExecutor executor,
                           DecisionSink sink, WebFilterChain chain) { }
}
