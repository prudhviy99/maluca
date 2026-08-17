package com.maluca.contracts.runbook;

/** Retrieved trusted runbook context and its similarity score. */
public record RunbookChunkView(
        String chunkId,
        String source,
        String heading,
        String content,
        double similarity) {
}
