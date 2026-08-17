package com.maluca.contracts.triage;

/** A citation must resolve to one of the exact chunks retrieved for a report. */
public record Citation(String chunkId, String source, String heading) {
}
