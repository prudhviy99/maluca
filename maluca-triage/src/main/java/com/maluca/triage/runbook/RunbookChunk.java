package com.maluca.triage.runbook;

/** Trusted, heading-sized unit of the runbook corpus. */
public record RunbookChunk(
        String chunkId,
        String source,
        String heading,
        String content,
        String sha256) {
}
