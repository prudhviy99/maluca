package com.maluca.scoring;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;
import com.maluca.model.RiskSignals;
import com.maluca.model.ScoreResult;
import com.maluca.model.UaClass;

/**
 * Weighted linear scorer. For each rate-style signal the contribution ramps
 * linearly once the configured threshold is breached:
 *
 * <pre>contribution = weight * clamp((value - threshold) / threshold, 0, 1.5)</pre>
 *
 * so value == threshold contributes nothing, value == 2x threshold contributes
 * the full weight, and the per-signal cap is 1.5x weight. Boolean signals
 * contribute their full weight. The total is clamped to 0–100.
 *
 * Deliberately simple: a linear combination means every decision decomposes
 * into "these signals pushed the score here", which is logged with every
 * decision.
 */
@Component
public class WeightedLinearScorer implements Scorer {

    private static final double RAMP_CAP = 1.5;

    private final MalucaProperties.Scoring cfg;

    public WeightedLinearScorer(MalucaProperties properties) {
        this.cfg = properties.scoring();
    }

    @Override
    public ScoreResult score(RiskSignals signals) {
        Map<String, Double> contributions = new LinkedHashMap<>();

        ramp(contributions, "burst_10s", signals.burst10s(),
                cfg.thresholds().burstPer10s(), cfg.weights().burst());
        ramp(contributions, "sustained_60s", signals.sustained60s(),
                cfg.thresholds().sustainedPer60s(), cfg.weights().sustained());
        ramp(contributions, "path_scan_30s", signals.distinctPaths30s(),
                cfg.thresholds().distinctPathsPer30s(), cfg.weights().pathScan());
        ramp(contributions, "sensitive_60s", signals.sensitiveHits60s(),
                cfg.thresholds().sensitiveHitsPer60s(), cfg.weights().sensitive());
        ramp(contributions, "fourxx_60s", signals.fourxx60s(),
                cfg.thresholds().fourxxPer60s(), cfg.weights().fourxx());

        if (signals.headerAnomalies() > 0) {
            contributions.put("header_anomaly",
                    (double) cfg.weights().headerAnomaly() * signals.headerAnomalies()
                            / SignalsCollectorConstants.MAX_HEADER_ANOMALIES);
        }

        double uaWeight = switch (signals.uaClass()) {
            case KNOWN_BAD_BOT -> cfg.weights().knownBadBot();
            case SCRIPT_CLIENT -> cfg.weights().scriptClient();
            case UNKNOWN -> cfg.weights().unknownUa();
            case BROWSER, MOBILE_APP, VERIFIED_BOT -> 0;
        };
        if (uaWeight > 0) {
            contributions.put("ua_class_" + signals.uaClass().name().toLowerCase(), uaWeight);
        }

        if (signals.limitExceeded()) {
            contributions.put("limit_exceeded", (double) cfg.weights().limitExceeded());
        }
        if (signals.priorEscalation()) {
            contributions.put("prior_escalation", (double) cfg.weights().priorEscalation());
        }
        if (signals.onDenylist()) {
            contributions.put("denylist", 100.0);
        }

        int total = (int) Math.round(Math.clamp(
                contributions.values().stream().mapToDouble(Double::doubleValue).sum(), 0, 100));
        return new ScoreResult(total, contributions);
    }

    private static void ramp(Map<String, Double> contributions, String name,
                             long value, long threshold, int weight) {
        if (threshold <= 0 || value <= threshold) {
            return;
        }
        double severity = Math.min((double) (value - threshold) / threshold, RAMP_CAP);
        contributions.put(name, weight * severity);
    }

    /** Keep in sync with the anomaly checks in {@link SignalsCollector}. */
    static final class SignalsCollectorConstants {
        static final double MAX_HEADER_ANOMALIES = 4.0;
    }
}
