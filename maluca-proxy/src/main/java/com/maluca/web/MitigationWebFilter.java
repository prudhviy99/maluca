package com.maluca.web;

import java.time.Duration;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.maluca.config.MalucaProperties;
import com.maluca.identity.ClientIdentityExtractor;
import com.maluca.metrics.MalucaMetrics;
import com.maluca.model.ClientIdentity;
import com.maluca.model.ClientState;
import com.maluca.model.MitigationAction;
import com.maluca.model.RateLimitAlgorithm;
import com.maluca.model.RateLimitConfig;
import com.maluca.proxy.ProxyService;
import com.maluca.proxy.SyntheticResponses;
import com.maluca.ratelimit.RateLimiter;
import com.maluca.state.ClientStateRepository;

import reactor.core.publisher.Mono;

/**
 * The front door. Every external request flows through here:
 * identity → behavioral state → decision → proxy or synthetic response.
 *
 * Maluca's own endpoints (/actuator, /_maluca) bypass the pipeline.
 */
@Component
public class MitigationWebFilter implements WebFilter, Ordered {

    private final ClientIdentityExtractor identityExtractor;
    private final ClientStateRepository stateRepository;
    private final RateLimiter rateLimiter;
    private final ProxyService proxyService;
    private final MalucaMetrics metrics;
    private final DecisionLogger decisionLogger;
    private final MalucaProperties properties;
    private final RateLimitConfig mvpLimit;

    public MitigationWebFilter(ClientIdentityExtractor identityExtractor,
                               ClientStateRepository stateRepository,
                               RateLimiter rateLimiter,
                               ProxyService proxyService,
                               MalucaMetrics metrics,
                               DecisionLogger decisionLogger,
                               MalucaProperties properties) {
        this.identityExtractor = identityExtractor;
        this.stateRepository = stateRepository;
        this.rateLimiter = rateLimiter;
        this.proxyService = proxyService;
        this.metrics = metrics;
        this.decisionLogger = decisionLogger;
        this.properties = properties;
        this.mvpLimit = RateLimitConfig.windowed(
                RateLimitAlgorithm.FIXED_WINDOW,
                properties.limits().maxRequests(),
                properties.limits().windowSeconds());
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
        boolean sensitive = isSensitive(path);

        return stateRepository.collect(identity.compositeKey(), path, sensitive)
                .flatMap(state -> decide(exchange, identity, state, path))
                .flatMap(decision -> execute(exchange, identity, decision, path, startNanos));
    }

    private Mono<Phase1Decision> decide(ServerWebExchange exchange, ClientIdentity identity,
                                        ClientState state, String path) {
        // 1. Sticky decisions (hysteresis): a recently blocked client stays blocked.
        if (state.hasStickyAction() && MitigationAction.valueOf(state.stickyAction()) == MitigationAction.BLOCK) {
            return Mono.just(new Phase1Decision(MitigationAction.BLOCK, state, 0, "sticky_block"));
        }

        // 2. Escalation to block: sustained abuse over the 60s window.
        if (state.countLast60s() > properties.limits().blockThresholdPer60s()) {
            return stateRepository.pinAction(identity.compositeKey(), MitigationAction.BLOCK,
                            Duration.ofMinutes(properties.limits().blockMinutes()))
                    .thenReturn(new Phase1Decision(MitigationAction.BLOCK, state, 0, "sustained_abuse"));
        }

        // 3. Fixed-window rate limit.
        if (!properties.limits().enabled()) {
            return Mono.just(new Phase1Decision(MitigationAction.ALLOW, state, 0, "limits_disabled"));
        }
        return rateLimiter.check(identity.compositeKey(), mvpLimit)
                .map(limit -> limit.allowed()
                        ? new Phase1Decision(MitigationAction.ALLOW, state, 0, "under_limit")
                        : new Phase1Decision(MitigationAction.HARD_LIMIT, state, limit.retryAfterSeconds(), "over_limit"));
    }

    private Mono<Void> execute(ServerWebExchange exchange, ClientIdentity identity,
                               Phase1Decision decision, String path, long startNanos) {
        metrics.recordAddedLatency(System.nanoTime() - startNanos);
        metrics.recordDecision(decision.action());
        decisionLogger.log(identity, decision.action(), path, decision.reason(), decision.state());

        return switch (decision.action()) {
            case ALLOW, OBSERVE, SOFT_LIMIT -> proxyService.forward(exchange, identity);
            case HARD_LIMIT -> SyntheticResponses.tooManyRequests(exchange, decision.retryAfterSeconds());
            case CHALLENGE, BLOCK -> SyntheticResponses.blocked(exchange);
        };
    }

    private boolean isSensitive(String path) {
        return properties.sensitivePaths().stream().anyMatch(path::startsWith);
    }

    private record Phase1Decision(MitigationAction action, ClientState state,
                                  long retryAfterSeconds, String reason) {
    }
}
