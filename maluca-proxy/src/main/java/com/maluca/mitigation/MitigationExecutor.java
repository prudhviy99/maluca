package com.maluca.mitigation;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.config.MalucaProperties;
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
    private final Duration softLimitDelay;

    public MitigationExecutor(ProxyService proxyService, MalucaProperties properties) {
        this.proxyService = proxyService;
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
            case CHALLENGE -> SyntheticResponses.json(exchange,
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "{\"error\":\"challenge_required\",\"message\":\"Challenge subsystem arrives in Phase 4.\"}");
            case BLOCK -> SyntheticResponses.blocked(exchange);
        };
    }
}
