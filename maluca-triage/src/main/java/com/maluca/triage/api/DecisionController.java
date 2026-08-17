package com.maluca.triage.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.decision.DecisionBatch;
import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.triage.decision.DecisionIngestService;
import com.maluca.triage.decision.DecisionQuery;
import com.maluca.triage.decision.DecisionRepository;

@RestController
public class DecisionController {

    private final DecisionIngestService ingestService;
    private final DecisionRepository repository;

    public DecisionController(DecisionIngestService ingestService, DecisionRepository repository) {
        this.ingestService = ingestService;
        this.repository = repository;
    }

    @PostMapping("/internal/v1/decisions")
    public ResponseEntity<DecisionIngestService.IngestResult> ingest(@RequestBody DecisionBatch batch) {
        return ResponseEntity.accepted().body(ingestService.ingest(batch));
    }

    @GetMapping("/api/v1/decisions")
    public List<DecisionEvent> decisions(
            @RequestParam(required = false) String policy,
            @RequestParam(name = "client_key", required = false) String clientKey,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        return repository.find(new DecisionQuery(policy, clientKey, action, from, to, clamp(limit, 1, 200)));
    }

    @GetMapping("/api/v1/signals")
    public Map<String, Object> signals(
            @RequestParam String policy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant safeTo = to == null ? Instant.now() : to;
        Instant safeFrom = from == null ? safeTo.minus(15, ChronoUnit.MINUTES) : from;
        if (safeFrom.isBefore(safeTo.minus(24, ChronoUnit.HOURS)) || !safeFrom.isBefore(safeTo)) {
            throw new IllegalArgumentException("signal range must be positive and no greater than 24 hours");
        }
        return Map.of(
                "policy", policy,
                "from", safeFrom,
                "to", safeTo,
                "contributionTotals", repository.signalBreakdown(policy, safeFrom, safeTo));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
