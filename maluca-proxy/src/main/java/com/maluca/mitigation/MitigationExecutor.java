package com.maluca.mitigation;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.challenge.ChallengeService;
import com.maluca.config.MalucaProperties;
import com.maluca.metrics.ChallengeMetrics;
import com.maluca.model.ClientIdentity;
import com.maluca.model.Decision;
import com.maluca.proxy.ProxyService;
import com.maluca.proxy.SyntheticResponses;

import reactor.core.publisher.Mono;

/**
 * Turns a {@link Decision} into actual behavior. Dry-run decisions are
 * executed as ALLOW — the decision is still logged and counted, which is how
 * thresholds get tuned safely against production traffic.
 */
@Component
public class MitigationExecutor {

    private final ProxyService proxyService;
    private final ChallengeService challengeService;
    private final ChallengeMetrics challengeMetrics;
    private final Duration softLimitDelay;

    public MitigationExecutor(ProxyService proxyService,
                              ChallengeService challengeService,
                              ChallengeMetrics challengeMetrics,
                              MalucaProperties properties) {
        this.proxyService = proxyService;
        this.challengeService = challengeService;
        this.challengeMetrics = challengeMetrics;
        this.softLimitDelay = Duration.ofMillis(properties.mitigation().softLimitDelayMs());
    }

    public Mono<Void> execute(ServerWebExchange exchange, ClientIdentity identity, Decision decision) {
        if (decision.dryRun()) {
            return proxyService.forward(exchange, identity);
        }
        return switch (decision.action()) {
            case ALLOW, OBSERVE -> proxyService.forward(exchange, identity);
            case SOFT_LIMIT -> Mono.delay(softLimitDelay)
                    .then(Mono.defer(() -> proxyService.forward(exchange, identity)));
            case HARD_LIMIT -> SyntheticResponses.tooManyRequests(exchange, decision.retryAfterSeconds());
            case CHALLENGE -> serveChallenge(exchange, identity, decision);
            case BLOCK -> SyntheticResponses.blocked(exchange);
        };
    }

    private Mono<Void> serveChallenge(ServerWebExchange exchange, ClientIdentity identity, Decision decision) {
        // datacenter-origin clients start one PoW difficulty level higher
        int datacenterBump = decision.contributions().containsKey("datacenter") ? 1 : 0;
        ChallengeService.IssuedChallenge challenge =
                challengeService.issue(identity.compositeKey(), decision.score(), datacenterBump);
        challengeMetrics.issued(challenge.type().name());

        if (acceptsHtml(exchange)) {
            return SyntheticResponses.html(exchange, HttpStatus.FORBIDDEN,
                    challengeService.renderPage(challenge));
        }
        // API clients get the raw material to solve programmatically
        return SyntheticResponses.json(exchange, HttpStatus.FORBIDDEN, """
                {"error":"challenge_required","type":"%s","token":"%s","difficultyBits":%d,\
                "verify":"POST /_maluca/challenge/verify {token, nonce}"}"""
                .formatted(challenge.type(), challenge.token(), challenge.difficultyBits()));
    }

    private static boolean acceptsHtml(ServerWebExchange exchange) {
        try {
            return exchange.getRequest().getHeaders().getAccept().stream()
                    .anyMatch(t -> t.isCompatibleWith(MediaType.TEXT_HTML));
        } catch (Exception e) {
            return false;
        }
    }
}
