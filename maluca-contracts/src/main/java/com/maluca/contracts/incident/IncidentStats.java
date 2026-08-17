package com.maluca.contracts.incident;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Compact evidence snapshot frozen when an incident is opened. */
public record IncidentStats(
        Instant windowStart,
        Instant windowEnd,
        long totalDecisions,
        long mitigatedDecisions,
        double mitigationShare,
        double baselineMitigationShare,
        double meanScore,
        int maxScore,
        long distinctClients,
        long distinctPaths,
        Map<String, Long> actionCounts,
        Map<String, Double> contributionTotals,
        List<CountedValue> topClients,
        List<CountedValue> topPaths) {

    public IncidentStats {
        actionCounts = actionCounts == null ? Map.of() : Map.copyOf(actionCounts);
        contributionTotals = contributionTotals == null ? Map.of() : Map.copyOf(contributionTotals);
        topClients = topClients == null ? List.of() : List.copyOf(topClients);
        topPaths = topPaths == null ? List.of() : List.copyOf(topPaths);
    }
}
