package com.maluca.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.maluca.config.MalucaProperties;
import com.maluca.identity.ClientIdentityExtractor;
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
    private final ClientStateRepository stateRepository;
    private final RateLimiter rateLimiter;
    private final SignalsCollector signalsCollector;
    private final Scorer scorer;
    private final PolicyResolver policyResolver;
    private final HysteresisService hysteresis;
    private final MitigationExecutor executor;
    private final ProxyService proxyService;
    private final MalucaMetrics metrics;
    private final DecisionLogger decisionLogger;
    private final MalucaProperties properties;
    private final RateLimitConfig baselineLimit;

    public MitigationWebFilter(ClientIdentityExtractor identityExtractor,
                               ClientStateRepository stateRepository,
                               RateLimiter rateLimiter,
                               SignalsCollector signalsCollector,
                               Scorer scorer,
                               PolicyResolver policyResolver,
                               HysteresisService hysteresis,
                               MitigationExecutor executor,
                               ProxyService proxyService,
                               MalucaMetrics metrics,
                               DecisionLogger decisionLogger,
                               MalucaProperties properties) {
        this.identityExtractor = identityExtractor;
        this.stateRepository = stateRepository;
        this.rateLimiter = rateLimiter;
        this.signalsCollector = signalsCollector;
        this.scorer = scorer;
        this.policyResolver = policyResolver;
        this.hysteresis = hysteresis;
        this.executor = executor;
        this.proxyService = proxyService;
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
        ClientIdentity identity = identityExtractor.extract(exchange);
        RequestMeta meta = RequestMeta.from(exchange.getRequest());
        boolean sensitive = isSensitive(path);

        return stateRepository.collect(identity.compositeKey(), path, sensitive)
                .flatMap(state -> checkBaselineLimit(identity)
                        .flatMap(limit -> decide(identity, meta, state, limit)))
                .flatMap(decision -> {
                    metrics.recordAddedLatency(System.nanoTime() - startNanos);
                    metrics.recordDecision(decision.action());
                    decisionLogger.log(identity, decision, path);
                    return executor.execute(exchange, identity, decision);
                });
    }

    private Mono<LimitDecision> checkBaselineLimit(ClientIdentity identity) {
        if (!properties.limits().enabled()) {
            return Mono.just(LimitDecision.allowedNoLimit());
        }
        return rateLimiter.check(identity.compositeKey(), baselineLimit);
    }

    private Mono<Decision> decide(ClientIdentity identity, RequestMeta meta,
                                  ClientState state, LimitDecision limit) {
        RiskSignals signals = signalsCollector.collect(meta, state, !limit.allowed());
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

    private boolean isSensitive(String path) {
        return properties.sensitivePaths().stream().anyMatch(path::startsWith);
    }
}
