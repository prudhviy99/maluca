package com.maluca.triage.decision;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maluca.triage.config.TriageProperties;

@Component
public class DecisionRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DecisionRetentionJob.class);
    private final DecisionRepository repository;
    private final TriageProperties properties;

    public DecisionRetentionJob(DecisionRepository repository, TriageProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${maluca.triage.retention.purge-cron:0 17 * * * *}")
    public void purge() {
        long deleted = repository.purgeBefore(Instant.now().minus(properties.retention().decisions()));
        if (deleted > 0) {
            log.info("decision_retention_purged count={}", deleted);
        }
    }
}
