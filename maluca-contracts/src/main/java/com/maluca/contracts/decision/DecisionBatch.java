package com.maluca.contracts.decision;

import java.util.List;

/** Bounded wire batch used by the proxy-to-triage ingestion channel. */
public record DecisionBatch(List<DecisionEvent> events) {

    public DecisionBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
