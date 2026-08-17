package com.maluca.triage.detection;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.triage.config.TriageProperties;

/** Pure, order-stable anomaly rules; no model participates in incident opening. */
@Component
public class AnomalyRuleEvaluator {

    private final TriageProperties.Detection config;

    public AnomalyRuleEvaluator(TriageProperties properties) {
        this.config = properties.detection();
    }

    public Optional<IncidentTrigger> evaluate(WindowAggregate window) {
        if (window.redisErrors() >= config.redisErrorThreshold()) {
            return Optional.of(IncidentTrigger.REDIS_DEGRADATION);
        }
        if (window.challengeOrBlock() >= config.challengeBlockThreshold()) {
            return Optional.of(IncidentTrigger.CHALLENGE_BLOCK_SURGE);
        }

        double currentShare = window.mitigationShare();
        double baselineShare = window.baselineMitigationShare();
        boolean shareSpike = window.mitigated() >= config.minimumMitigated()
                && currentShare >= config.minimumMitigationShare()
                && (baselineShare == 0 || currentShare >= baselineShare * config.mitigationMultiplier());
        if (shareSpike) {
            return Optional.of(IncidentTrigger.MITIGATION_SPIKE);
        }

        double expected = normalizedBaselineVolume(window);
        boolean volumeSpike = window.total() >= config.trafficVolumeFloor()
                && (expected == 0 || window.total() >= expected * config.trafficVolumeMultiplier());
        if (volumeSpike) {
            return Optional.of(IncidentTrigger.TRAFFIC_VOLUME_SURGE);
        }
        return Optional.empty();
    }

    private double normalizedBaselineVolume(WindowAggregate window) {
        Duration current = config.currentWindow();
        Duration baseline = config.baselineWindow();
        if (baseline.isZero() || baseline.isNegative()) {
            return 0;
        }
        return window.baselineTotal() * ((double) current.toMillis() / baseline.toMillis());
    }
}
