package com.maluca.triage.detection;

import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.incident.IncidentRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(prefix = "maluca.triage.detection", name = "enabled", havingValue = "true")
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);
    private static final long ADVISORY_LOCK_ID = 0x4d414c554341L;

    private final DetectionRepository detectionRepository;
    private final IncidentRepository incidentRepository;
    private final AnomalyRuleEvaluator evaluator;
    private final PrometheusRedisSignalClient prometheus;
    private final JdbcTemplate jdbc;
    private final TriageProperties.Detection config;
    private final Counter opened;
    private final Counter resolved;

    public AnomalyDetector(DetectionRepository detectionRepository, IncidentRepository incidentRepository,
                           AnomalyRuleEvaluator evaluator, JdbcTemplate jdbc,
                           PrometheusRedisSignalClient prometheus,
                           TriageProperties properties, MeterRegistry meters) {
        this.detectionRepository = detectionRepository;
        this.incidentRepository = incidentRepository;
        this.evaluator = evaluator;
        this.prometheus = prometheus;
        this.jdbc = jdbc;
        this.config = properties.detection();
        this.opened = Counter.builder("maluca.triage.incidents.opened").register(meters);
        this.resolved = Counter.builder("maluca.triage.incidents.resolved").register(meters);
    }

    @Scheduled(fixedDelayString = "${maluca.triage.detection.poll-interval:15s}")
    @Transactional
    public void detect() {
        if (!tryLock()) {
            return;
        }
        Instant now = Instant.now();
        Instant currentStart = now.minus(config.currentWindow());
        Instant baselineEnd = currentStart;
        Instant baselineStart = baselineEnd.minus(config.baselineWindow());
        Set<String> anomalousPolicies = new HashSet<>();

        List<WindowAggregate> windows = new ArrayList<>(detectionRepository.aggregate(
                currentStart, now, baselineStart, baselineEnd));
        var redisSignal = prometheus.redisErrorIncrease(config.currentWindow());
        double redisIncrease = redisSignal.orElse(0);
        if (redisIncrease >= config.redisErrorThreshold()) {
            windows.add(new WindowAggregate(
                    "__maluca_redis__", "/_maluca/redis", 0, 0, 0,
                    Math.max(1, (long) Math.ceil(redisIncrease)), 0, 0, 0, 0, 0, 0));
        }

        for (WindowAggregate window : windows) {
            evaluator.evaluate(window).ifPresent(trigger -> {
                anomalousPolicies.add(window.policyName());
                boolean wasAlreadyOpen = incidentRepository.findActive(window.policyName()).isPresent();
                var snapshot = detectionRepository.snapshot(
                        window, currentStart, now, config.topValueLimit());
                IncidentView incident = incidentRepository.openOrTouch(
                        window.policyName(), window.policyRoute(), trigger, snapshot, now);
                if (!wasAlreadyOpen) {
                    opened.increment();
                    log.warn("incident_opened id={} policy={} trigger={}",
                            incident.id(), incident.policyName(), trigger);
                }
            });
        }

        Instant inactiveBefore = now.minus(config.resolveAfter());
        for (IncidentView incident : incidentRepository.findInactiveUnresolved(inactiveBefore)) {
            if ("__maluca_redis__".equals(incident.policyName()) && redisSignal.isEmpty()) {
                continue;
            }
            if (!anomalousPolicies.contains(incident.policyName())) {
                incidentRepository.resolve(incident.id(), now);
                resolved.increment();
                log.info("incident_resolved id={} policy={}", incident.id(), incident.policyName());
            }
        }
    }

    private boolean tryLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_ID));
    }
}
