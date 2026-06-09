package com.maluca.web;

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
import com.maluca.model.UaClass;
import com.maluca.metrics.MalucaMetrics;
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
import com.maluca.proxy.ProxyService;
import com.maluca.ratelimit.RateLimiter;
import com.maluca.scoring.Scorer;
import com.maluca.scoring.SignalsCollector;
import com.maluca.state.ClientStateRepository;

import reactor.core.publisher.Mono;

/**
 * The front door. Every external request flows through:
 *
 * <pre>identity → state (1 Redis trip) → rate limit → signals → score → band → hysteresis → execute</pre>
 *
 * Maluca's own endpoints (/actuator, /_maluca) bypass the pipeline.
 */
@Component
public class MitigationWebFilter implements WebFilter, Ordered {

    private final ClientIdentityExtractor identityExtractor;
    private final UaClassifier uaClassifier;
    private final VerifiedBotService verifiedBotService;
    private final DatacenterDetector datacenterDetector;
    private final ClientStateRepository stateRepository;
    private final RateLimiter rateLimiter;
    private final SignalsCollector signalsCollector;
    private final Scorer scorer;
    private final PolicyResolver policyResolver;
    private final HysteresisService hysteresis;
    private final MitigationExecutor executor;
    private final ProxyService proxyService;
    private final ChallengeService challengeService;
    private final ChallengeMetrics challengeMetrics;
    private final MalucaMetrics metrics;
    private final DecisionLogger decisionLogger;
    private final MalucaProperties properties;
    private final RateLimitConfig baselineLimit;

    public MitigationWebFilter(ClientIdentityExtractor identityExtractor,
                               UaClassifier uaClassifier,
                               VerifiedBotService verifiedBotService,
                               DatacenterDetector datacenterDetector,
                               ClientStateRepository stateRepository,
                               RateLimiter rateLimiter,
                               SignalsCollector signalsCollector,
                               Scorer scorer,
                               PolicyResolver policyResolver,
                               HysteresisService hysteresis,
                               MitigationExecutor executor,
                               ProxyService proxyService,
                               ChallengeService challengeService,
                               ChallengeMetrics challengeMetrics,
                               MalucaMetrics metrics,
                               DecisionLogger decisionLogger,
                               MalucaProperties properties) {
        this.identityExtractor = identityExtractor;
        this.uaClassifier = uaClassifier;
        this.verifiedBotService = verifiedBotService;
        this.datacenterDetector = datacenterDetector;
        this.stateRepository = stateRepository;
        this.rateLimiter = rateLimiter;
        this.signalsCollector = signalsCollector;
        this.scorer = scorer;
        this.policyResolver = policyResolver;
        this.hysteresis = hysteresis;
        this.executor = executor;
        this.proxyService = proxyService;
        this.challengeService = challengeService;
        this.challengeMetrics = challengeMetrics;
        this.metrics = metrics;
        this.decisionLogger = decisionLogger;
        this.properties = properties;
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
        ClientIdentity identity = identityExtractor.extract(exchange, meta);

        // A valid signed pass cookie (issued on challenge success) bypasses
        // the scorer entirely for its TTL — the client already proved itself.
        if (hasValidPass(exchange, identity)) {
            challengeMetrics.passBypass();
            return proxyService.forward(exchange, identity);
        }

        boolean sensitive = isSensitive(path);
        boolean datacenter = datacenterDetector.isDatacenter(identity.ip());

        return resolveUaClass(meta, identity.ip())
                .flatMap(uaClass -> stateRepository.collect(identity.compositeKey(), path, sensitive)
                        .flatMap(state -> checkBaselineLimit(identity)
                                .flatMap(limit -> decide(identity, meta, state, limit, uaClass, datacenter))))
                .flatMap(decision -> {
                    metrics.recordAddedLatency(System.nanoTime() - startNanos);
                    metrics.recordDecision(decision.action());
                    decisionLogger.log(identity, decision, path);
                    return executor.execute(exchange, identity, decision);
                });
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

    private Mono<LimitDecision> checkBaselineLimit(ClientIdentity identity) {
        if (!properties.limits().enabled()) {
            return Mono.just(LimitDecision.allowedNoLimit());
        }
        return rateLimiter.check(identity.compositeKey(), baselineLimit);
    }

    private Mono<Decision> decide(ClientIdentity identity, RequestMeta meta, ClientState state,
                                  LimitDecision limit, UaClass uaClass, boolean datacenter) {
        RiskSignals signals = signalsCollector.collect(meta, state, uaClass, datacenter, !limit.allowed());
        ScoreResult score = scorer.score(signals);

        MitigationAction scored = policyResolver.resolve(score.score());
        // A breached baseline limit floors the action at HARD_LIMIT regardless of score.
        if (!limit.allowed() && MitigationAction.HARD_LIMIT.isAtLeastAsSevereAs(scored)) {
            scored = MitigationAction.HARD_LIMIT;
        }

        MitigationAction effective = hysteresis.applyFloor(scored, state);
        String reason = effective != scored ? "sticky_" + effective.name().toLowerCase()
                : !limit.allowed() ? "limit_exceeded"
                : "score_band";

        Decision decision = Decision.of(effective, score.score(), reason, score.contributions())
                .withRetryAfter(limit.allowed() ? retryAfterFromHysteresis(effective) : limit.retryAfterSeconds());

        return hysteresis.maybePin(identity.compositeKey(), scored)
                .thenReturn(decision);
    }

    private long retryAfterFromHysteresis(MitigationAction action) {
        return action == MitigationAction.HARD_LIMIT
                ? properties.hysteresis().hardLimitTtlSeconds()
                : 0;
    }

    private boolean hasValidPass(ServerWebExchange exchange, ClientIdentity identity) {
        var cookie = exchange.getRequest().getCookies().getFirst("maluca_pass");
        return cookie != null && challengeService.isValidPass(cookie.getValue(), identity.compositeKey());
    }

    private boolean isSensitive(String path) {
        return properties.sensitivePaths().stream().anyMatch(path::startsWith);
    }
}
