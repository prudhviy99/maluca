package com.maluca.triage.incident;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.triage.config.TriageProperties;

/** Deterministic, capped exponential backoff for failed or expired triage claims. */
@Component
public class TriageRetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;

    public TriageRetryPolicy(TriageProperties properties) {
        this.maxAttempts = properties.agent().maxAttempts();
        this.baseDelay = properties.agent().retryBaseDelay();
        this.maxDelay = properties.agent().retryMaxDelay();
    }

    public FailurePlan afterFailure(int attempts, Instant now) {
        int boundedAttempts = Math.max(1, attempts);
        if (boundedAttempts >= maxAttempts) {
            return new FailurePlan(IncidentStatus.TRIAGE_FAILED, null);
        }
        int exponent = Math.min(30, boundedAttempts - 1);
        long multiplier = 1L << exponent;
        Duration candidate;
        try {
            candidate = baseDelay.multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            candidate = maxDelay;
        }
        Duration delay = candidate.compareTo(maxDelay) > 0 ? maxDelay : candidate;
        return new FailurePlan(IncidentStatus.OPEN, now.plus(delay));
    }

    public record FailurePlan(IncidentStatus status, Instant nextAttemptAt) {
        public boolean terminal() {
            return status == IncidentStatus.TRIAGE_FAILED;
        }
    }
}
