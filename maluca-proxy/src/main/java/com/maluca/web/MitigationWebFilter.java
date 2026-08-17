package com.maluca.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.maluca.challenge.ChallengeService;
import com.maluca.config.MalucaProperties;
import com.maluca.identity.ClientIdentityExtractor;
import com.maluca.identity.DatacenterDetector;
import com.maluca.identity.UaClassifier;
import com.maluca.identity.VerifiedBotService;
import com.maluca.metrics.ChallengeMetrics;
import com.maluca.metrics.MalucaMetrics;
import com.maluca.metrics.Observed;

import io.micrometer.observation.ObservationRegistry;
import com.maluca.mitigation.HysteresisService;
import com.maluca.mitigation.MitigationExecutor;
import com.maluca.mitigation.PolicyResolver;
import com.maluca.model.ClientIdentity;
import com.maluca.model.ClientState;
import com.maluca.model.Decision;
import com.maluca.model.LimitDecision;
import com.maluca.model.MitigationAction;
import com.maluca.model.RateLimitConfig;
import com.maluca.model.RequestMeta;
import com.maluca.model.RiskSignals;
import com.maluca.model.ScoreResult;
import com.maluca.model.UaClass;
import com.maluca.policy.ClientTierService;
import com.maluca.policy.CompiledPolicy;
import com.maluca.policy.PolicyDefinition;
import com.maluca.policy.PolicyRegistry;
import com.maluca.ratelimit.RateLimiter;
import com.maluca.scoring.Scorer;
import com.maluca.scoring.SignalsCollector;
import com.maluca.state.ClientStateRepository;
import com.maluca.state.RedisCircuitBreaker;

import reactor.core.publisher.Mono;

/**
 * The front door. Every external request flows through:
 *
 * <pre>tier → policy → identity (policy keying) → allow/denylist
 *   → state (1 Redis trip) → rate limit (policy algorithm) → signals
 *   → score → bands (policy) → hysteresis → mode (dry-run) → execute</pre>
 *
 * Maluca's own endpoints (/actuator, /_maluca) bypass the pipeline.
 */
@Component
public class MitigationWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MitigationWebFilter.class);

    private final ClientIdentityExtractor identityExtractor;
    private final UaClassifier uaClassifier;
    private final VerifiedBotService verifiedBotService;
    private final DatacenterDetector datacenterDetector;
    private final ClientTierService tierService;
    private final PolicyRegistry policyRegistry;
    private final ClientStateRepository stateRepository;
    private final RedisCircuitBreaker breaker;
    private final RateLimiter rateLimiter;
    private final SignalsCollector signalsCollector;
    private final Scorer scorer;
    private final HysteresisService hysteresis;
    private final MitigationExecutor executor;
    private final ChallengeService challengeService;
    private final ChallengeMetrics challengeMetrics;
    private final MalucaMetrics metrics;
    private final DecisionLogger decisionLogger;
    private final DecisionEventFactory decisionEventFactory;
    private final DecisionSink decisionSink;
    private final MalucaProperties properties;
    private final ObservationRegistry observations;
    private final RateLimitConfig baselineLimit;

    public MitigationWebFilter(ClientIdentityExtractor identityExtractor,
                               UaClassifier uaClassifier,
                               VerifiedBotService verifiedBotService,
                               DatacenterDetector datacenterDetector,
                               ClientTierService tierService,
                               PolicyRegistry policyRegistry,
                               ClientStateRepository stateRepository,
                               RedisCircuitBreaker breaker,
                               RateLimiter rateLimiter,
                               SignalsCollector signalsCollector,
                               Scorer scorer,
                               HysteresisService hysteresis,
                               MitigationExecutor executor,
                               ChallengeService challengeService,
                               ChallengeMetrics challengeMetrics,
                               MalucaMetrics metrics,
                               DecisionLogger decisionLogger,
                               DecisionEventFactory decisionEventFactory,
                               DecisionSink decisionSink,
                               MalucaProperties properties,
                               ObservationRegistry observations) {
        this.identityExtractor = identityExtractor;
        this.uaClassifier = uaClassifier;
        this.verifiedBotService = verifiedBotService;
        this.datacenterDetector = datacenterDetector;
        this.tierService = tierService;
        this.policyRegistry = policyRegistry;
        this.stateRepository = stateRepository;
        this.breaker = breaker;
        this.rateLimiter = rateLimiter;
        this.signalsCollector = signalsCollector;
        this.scorer = scorer;
        this.hysteresis = hysteresis;
        this.executor = executor;
        this.challengeService = challengeService;
        this.challengeMetrics = challengeMetrics;
        this.metrics = metrics;
        this.decisionLogger = decisionLogger;
        this.decisionEventFactory = decisionEventFactory;
        this.decisionSink = decisionSink;
        this.properties = properties;
        this.observations = observations;
        this.baselineLimit = properties.limits().toConfig();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path.startsWith("/actuator") || path.startsWith("/_maluca")) {
            return chain.filter(exchange);
        }

        long startNanos = System.nanoTime();
        RequestMeta meta = RequestMeta.from(exchange.getRequest());
        String tier = tierService.tierOf(exchange.getRequest());
        CompiledPolicy policy = policyRegistry.resolve(path, tier);
        ClientIdentity identity = identityExtractor.extract(exchange, meta,
                policy != null ? policy.keying() : null);

        // A valid signed pass cookie (issued on challenge success) bypasses
        // the scorer entirely for its TTL — the client already proved itself.
        if (hasValidPass(exchange, identity)) {
            challengeMetrics.passBypass();
            Decision allowed = Decision.of(MitigationAction.ALLOW, 0,
                    "pass_cookie_bypass", java.util.Map.of());
            return finish(exchange, identity, allowed, policy, tier, path, startNanos);
        }

        // Policy allowlist: trusted sources (internal nets, partners) skip everything.
        if (policy != null && policy.allowlist().contains(identity.ip())) {
            Decision allowed = Decision.of(MitigationAction.ALLOW, 0, "allowlist", java.util.Map.of());
            return finish(exchange, identity, allowed, policy, tier, path, startNanos);
        }
        // Policy denylist: immediate block, no scoring needed.
        if (policy != null && policy.denylist().contains(identity.ip())) {
            Decision denied = Decision.of(MitigationAction.BLOCK, 100, "denylist", java.util.Map.of());
            denied = suppressOutsideEnforce(denied, policy);
            return finish(exchange, identity, denied, policy, tier, path, startNanos);
        }

        // Redis open-circuit: skip the whole stateful pipeline and apply the
        // route's fail-mode immediately (degradation tier PASSTHROUGH/closed).
        if (!stateRepository.redisHealthy()) {
            return finish(exchange, identity, degradedDecision(policy), policy, tier, path, startNanos);
        }

        boolean sensitive = isSensitive(path);
        boolean datacenter = datacenterDetector.isDatacenter(identity.ip());

        return resolveUaClass(meta, identity.ip())
                .flatMap(uaClass -> Observed.mono(observations, "maluca.state",
                                stateRepository.collect(identity.compositeKey(), path, sensitive))
                        .flatMap(state -> Observed.mono(observations, "maluca.ratelimit",
                                        checkLimit(identity, policy))
                                .flatMap(limit -> decide(identity, meta, state, limit, uaClass,
                                        datacenter, policy))))
                .flatMap(decision -> finish(exchange, identity, decision, policy, tier, path, startNanos));
    }

    private Mono<Void> finish(ServerWebExchange exchange, ClientIdentity identity,
                              Decision decision, CompiledPolicy policy, String tier,
                              String path, long startNanos) {
        metrics.recordAddedLatency(System.nanoTime() - startNanos);
        metrics.recordDecision(decision.action(),
                policy != null ? policy.name() : "none",
                policy != null ? policy.mode().name() : "ENFORCE");
        decisionLogger.log(identity, decision, path, policy != null ? policy.name() : "none");
        if (decisionSink.isEnabled()) {
            try {
                decisionSink.offer(decisionEventFactory.create(
                        exchange, identity, decision, policy, tier, path));
            } catch (RuntimeException e) {
                // Decision export is observability/control-plane traffic. It must
                // never alter the response chosen by the mitigation pipeline.
                log.warn("decision_sink_offer_failed error={}", e.toString());
            }
        }
        return executor.execute(exchange, identity, decision);
    }

    /**
     * UA classes are claims; the VERIFIED_BOT claim is the one worth lying
     * about, so it gets checked with forward-confirmed reverse DNS (cached).
     * A failed check reclassifies the client as a spoofer.
     */
    private Mono<UaClass> resolveUaClass(RequestMeta meta, String ip) {
        UaClass claimed = uaClassifier.classify(meta.userAgent());
        if (claimed != UaClass.VERIFIED_BOT) {
            return Mono.just(claimed);
        }
        return verifiedBotService.isVerifiedBot(ip)
                .map(verified -> verified ? UaClass.VERIFIED_BOT : UaClass.KNOWN_BAD_BOT);
    }

    private Mono<LimitDecision> checkLimit(ClientIdentity identity, CompiledPolicy policy) {
        RateLimitConfig config = policy != null && policy.rateLimit() != null
                ? policy.rateLimit()
                : (properties.limits().enabled() ? baselineLimit : null);
        if (config == null) {
            return Mono.just(LimitDecision.allowedNoLimit());
        }
        // key includes the policy so per-route limits don't share counters
        String key = (policy != null ? policy.name() : "global") + ":" + identity.compositeKey();
        // breaker-guarded: a Redis failure yields "allowed" here; the
        // fail-open/closed decision is applied in decide() from redis health
        return breaker.run(rateLimiter.check(key, config), LimitDecision.allowedNoLimit());
    }

    /**
     * When Redis is down, scoring and limiting can't run. Honor the route's
     * fail-mode: fail-closed routes (e.g. /login) BLOCK rather than admit an
     * unchecked credential-stuffing burst; everything else fails open.
     */
    private Decision degradedDecision(CompiledPolicy policy) {
        boolean failClosed = policy != null
                ? policy.failMode() == PolicyDefinition.FailMode.FAIL_CLOSED
                : !properties.resilience().failOpenByDefault();
        MitigationAction action = failClosed ? MitigationAction.BLOCK : MitigationAction.ALLOW;
        Decision decision = Decision.of(action, failClosed ? 100 : 0,
                "redis_down_fail_" + (failClosed ? "closed" : "open"), java.util.Map.of());
        return suppressOutsideEnforce(decision, policy);
    }

    private Mono<Decision> decide(ClientIdentity identity, RequestMeta meta, ClientState state,
                                  LimitDecision limit, UaClass uaClass, boolean datacenter,
                                  CompiledPolicy policy) {
        RiskSignals signals = signalsCollector.collect(meta, state, uaClass, datacenter, !limit.allowed());
        ScoreResult score = scorer.score(signals);

        MalucaProperties.Bands bands = policy != null && policy.bands() != null
                ? policy.bands() : properties.bands();
        MitigationAction scored = PolicyResolver.resolve(score.score(), bands);
        // A breached rate limit floors the action at HARD_LIMIT regardless of score.
        if (!limit.allowed() && MitigationAction.HARD_LIMIT.isAtLeastAsSevereAs(scored)) {
            scored = MitigationAction.HARD_LIMIT;
        }

        MitigationAction effective = hysteresis.applyFloor(scored, state);
        String reason = effective != scored ? "sticky_" + effective.name().toLowerCase()
                : !limit.allowed() ? "limit_exceeded"
                : "score_band";

        Decision decision = Decision.of(effective, score.score(), reason, score.contributions())
                .withRetryAfter(limit.allowed() ? retryAfterFromHysteresis(effective) : limit.retryAfterSeconds());

        // OBSERVE/DRY_RUN modes: full pipeline runs (including hysteresis
        // pinning, so flipping to ENFORCE later behaves identically), but the
        // executed action is pass-through.
        decision = suppressOutsideEnforce(decision, policy);

        MitigationAction toPin = scored;
        return hysteresis.maybePin(identity.compositeKey(), toPin)
                .thenReturn(decision);
    }

    private long retryAfterFromHysteresis(MitigationAction action) {
        return action == MitigationAction.HARD_LIMIT
                ? properties.hysteresis().hardLimitTtlSeconds()
                : 0;
    }

    private static Decision suppressOutsideEnforce(Decision decision, CompiledPolicy policy) {
        return policy != null && policy.mode() != PolicyDefinition.Mode.ENFORCE
                ? decision.asDryRun()
                : decision;
    }

    private boolean hasValidPass(ServerWebExchange exchange, ClientIdentity identity) {
        var cookie = exchange.getRequest().getCookies().getFirst("maluca_pass");
        return cookie != null && challengeService.isValidPass(cookie.getValue(), identity.compositeKey());
    }

    private boolean isSensitive(String path) {
        return properties.sensitivePaths().stream().anyMatch(path::startsWith);
    }
}
