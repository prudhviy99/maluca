package com.maluca.triage.agent;

import java.util.UUID;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.decision.DecisionQuery;
import com.maluca.triage.decision.DecisionRepository;
import com.maluca.triage.incident.IncidentClaim;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.incident.TriageRetryPolicy;
import com.maluca.triage.runbook.RunbookReadiness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class IncidentTriageWorker {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriageWorker.class);

    private final IncidentRepository incidents;
    private final DecisionRepository decisions;
    private final IncidentBriefFactory briefs;
    private final TriageAgent agent;
    private final IncidentTriageCompletion completion;
    private final TriageRetryPolicy retryPolicy;
    private final TriageProperties properties;
    private final RunbookReadiness runbooks;
    private final boolean enabled;
    private final Counter succeeded;
    private final Counter fallback;
    private final Counter deferred;
    private final Counter terminal;
    private final Counter reclaimed;
    private final Counter staleResults;

    public IncidentTriageWorker(IncidentRepository incidents, DecisionRepository decisions,
                                IncidentBriefFactory briefs, TriageAgent agent,
                                IncidentTriageCompletion completion, TriageRetryPolicy retryPolicy,
                                RunbookReadiness runbooks,
                                TriageProperties properties,
                                MeterRegistry meters) {
        this.incidents = incidents;
        this.decisions = decisions;
        this.briefs = briefs;
        this.agent = agent;
        this.completion = completion;
        this.retryPolicy = retryPolicy;
        this.runbooks = runbooks;
        this.properties = properties;
        this.enabled = properties.agent().enabled();
        this.succeeded = Counter.builder("maluca.triage.agent.reports").tag("result", "valid").register(meters);
        this.fallback = Counter.builder("maluca.triage.agent.reports").tag("result", "fallback").register(meters);
        this.deferred = Counter.builder("maluca.triage.agent.claims")
                .tag("result", "deferred").register(meters);
        this.terminal = Counter.builder("maluca.triage.agent.claims")
                .tag("result", "manual_review").register(meters);
        this.reclaimed = Counter.builder("maluca.triage.agent.claims")
                .tag("result", "lease_reclaimed").register(meters);
        this.staleResults = Counter.builder("maluca.triage.agent.claims")
                .tag("result", "stale_result").register(meters);
    }

    @Scheduled(fixedDelayString = "${maluca.triage.agent.poll-interval:10s}")
    public void triageNext() {
        if (!enabled || !runbooks.isReady()) {
            return;
        }
        Instant now = Instant.now();
        reclaimExpiredClaims(now);
        var claimed = incidents.claimNextEligible(now);
        if (claimed.isEmpty()) {
            return;
        }
        try {
            process(claimed.get());
        } catch (Exception e) {
            fail(claimed.get(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverExpiredClaimsOnStartup() {
        if (enabled) {
            reclaimExpiredClaims(Instant.now());
        }
    }

    public void triage(UUID incidentId) {
        if (!enabled) {
            throw new IllegalStateException("triage agent is disabled");
        }
        runbooks.requireReady();
        var incident = incidents.find(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        var claim = incidents.claimForManualTriage(incident.id(), Instant.now())
                .orElseThrow(() -> new IllegalStateException(
                        "incident must be OPEN or TRIAGE_FAILED and unclaimed for manual triage"));
        try {
            if (!process(claim)) {
                throw new IllegalStateException("triage lease expired before the report could be committed");
            }
        } catch (RuntimeException e) {
            fail(claim, e);
            throw e;
        }
    }

    private boolean process(IncidentClaim claim) {
        var incident = claim.incident();
        var samples = decisions.find(new DecisionQuery(incident.policyName(), null, null,
                incident.stats().windowStart(), incident.stats().windowEnd(),
                properties.detection().sampleLimit()));
        var output = agent.triage(incident, briefs.create(incident, samples));
        if (!output.valid()) {
            String failure = "model output failed validation: "
                    + String.join("; ", output.validationErrors());
            var plan = retryPolicy.afterFailure(claim.attempt(), Instant.now());
            var outcome = completion.completeFallback(claim, output, plan, failure);
            if (outcome.isEmpty()) {
                staleResults.increment();
                return false;
            }
            fallback.increment();
            if (outcome.get().terminal()) {
                terminal.increment();
                log.error("incident_triage_fallback_manual_review id={} attempt={} errors={}",
                        incident.id(), claim.attempt(), safeText(failure));
            } else {
                deferred.increment();
                log.warn("incident_triage_fallback_deferred id={} attempt={} next_attempt_at={}",
                        incident.id(), claim.attempt(), outcome.get().nextAttemptAt());
            }
            return true;
        }
        if (!completion.complete(claim, output)) {
            staleResults.increment();
            log.warn("incident_triage_result_discarded id={} lease={} reason=stale_lease",
                    incident.id(), claim.leaseId());
            return false;
        }
        succeeded.increment();
        log.info("incident_triaged id={} classification={} valid={} attempt={}",
                incident.id(), output.result().classification(), output.valid(), claim.attempt());
        return true;
    }

    private void fail(IncidentClaim claim, Exception error) {
        String failure = safeFailure(error);
        var plan = retryPolicy.afterFailure(claim.attempt(), Instant.now());
        incidents.recordTriageFailure(claim, plan, failure).ifPresentOrElse(outcome -> {
            if (outcome.terminal()) {
                terminal.increment();
                log.error("incident_triage_manual_review id={} attempt={} error={}",
                        outcome.incidentId(), outcome.attempt(), failure);
            } else {
                deferred.increment();
                log.warn("incident_triage_deferred id={} attempt={} next_attempt_at={} error={}",
                        outcome.incidentId(), outcome.attempt(), outcome.nextAttemptAt(), failure);
            }
        }, () -> log.warn("incident_triage_failure_discarded id={} lease={} reason=stale_lease",
                claim.incident().id(), claim.leaseId()));
    }

    private void reclaimExpiredClaims(Instant now) {
        Instant cutoff = now.minus(properties.agent().leaseTimeout());
        for (var outcome : incidents.reclaimExpiredClaims(cutoff, now, retryPolicy)) {
            if (outcome.terminal()) {
                terminal.increment();
                log.error("incident_triage_lease_expired_manual_review id={} attempt={}",
                        outcome.incidentId(), outcome.attempt());
            } else {
                reclaimed.increment();
                log.warn("incident_triage_lease_reclaimed id={} attempt={} next_attempt_at={}",
                        outcome.incidentId(), outcome.attempt(), outcome.nextAttemptAt());
            }
        }
    }

    private static String safeFailure(Exception error) {
        String message = error.getMessage();
        String value = error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        StringBuilder safe = new StringBuilder(Math.min(2_000, value.length()));
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int characters = Character.charCount(codePoint);
            if (safe.length() + characters > 2_000) {
                break;
            }
            safe.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint);
            offset += characters;
        }
        return safe.toString();
    }

    private static String safeText(String value) {
        return safeFailure(new IllegalStateException(value));
    }
}
