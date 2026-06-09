package com.maluca.mitigation;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;
import com.maluca.model.ClientState;
import com.maluca.model.MitigationAction;
import com.maluca.state.ClientStateRepository;

import reactor.core.publisher.Mono;

/**
 * Prevents clients from flapping between mitigation bands. Two rules:
 *
 * <ol>
 *   <li><b>Floor:</b> an active sticky action is the minimum severity applied,
 *       even if the current score has dropped.</li>
 *   <li><b>Pin:</b> when the freshly scored action reaches HARD_LIMIT or
 *       above, it is (re)pinned with a severity-dependent TTL.</li>
 * </ol>
 */
@Component
public class HysteresisService {

    private final ClientStateRepository stateRepository;
    private final MalucaProperties.Hysteresis cfg;

    public HysteresisService(ClientStateRepository stateRepository, MalucaProperties properties) {
        this.stateRepository = stateRepository;
        this.cfg = properties.hysteresis();
    }

    /** Applies the sticky floor from prior escalations. */
    public MitigationAction applyFloor(MitigationAction scored, ClientState state) {
        if (!state.hasStickyAction()) {
            return scored;
        }
        try {
            MitigationAction sticky = MitigationAction.valueOf(state.stickyAction());
            return sticky.isAtLeastAsSevereAs(scored) ? sticky : scored;
        } catch (IllegalArgumentException e) {
            return scored;
        }
    }

    /** Pins escalated actions; a no-op for ALLOW/OBSERVE/SOFT_LIMIT. */
    public Mono<Void> maybePin(String clientKey, MitigationAction scored) {
        Duration ttl = switch (scored) {
            case HARD_LIMIT -> Duration.ofSeconds(cfg.hardLimitTtlSeconds());
            case CHALLENGE -> Duration.ofSeconds(cfg.challengeTtlSeconds());
            case BLOCK -> Duration.ofSeconds(cfg.blockTtlSeconds());
            default -> null;
        };
        if (ttl == null || ttl.isZero()) {
            return Mono.empty();
        }
        return stateRepository.pinAction(clientKey, scored, ttl).then();
    }
}
