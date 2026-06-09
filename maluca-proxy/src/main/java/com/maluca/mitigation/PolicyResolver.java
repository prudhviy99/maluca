package com.maluca.mitigation;

import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;
import com.maluca.model.MitigationAction;

/**
 * Maps a 0–100 risk score to a mitigation action via configured bands.
 * Bands are half-open: score >= blockMin → BLOCK, else >= challengeMin →
 * CHALLENGE, and so on down to ALLOW.
 */
@Component
public class PolicyResolver {

    private final MalucaProperties.Bands bands;

    public PolicyResolver(MalucaProperties properties) {
        this.bands = properties.bands();
    }

    public MitigationAction resolve(int score) {
        return resolve(score, bands);
    }

    public static MitigationAction resolve(int score, MalucaProperties.Bands bands) {
        if (score >= bands.blockMin()) {
            return MitigationAction.BLOCK;
        }
        if (score >= bands.challengeMin()) {
            return MitigationAction.CHALLENGE;
        }
        if (score >= bands.hardLimitMin()) {
            return MitigationAction.HARD_LIMIT;
        }
        if (score >= bands.softLimitMin()) {
            return MitigationAction.SOFT_LIMIT;
        }
        if (score >= bands.observeMin()) {
            return MitigationAction.OBSERVE;
        }
        return MitigationAction.ALLOW;
    }
}
