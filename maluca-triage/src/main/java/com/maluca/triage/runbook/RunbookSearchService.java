package com.maluca.triage.runbook;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.triage.config.TriageProperties;

@Service
public class RunbookSearchService {

    private final VectorStore vectorStore;
    private final TriageProperties properties;
    private final RunbookReadiness readiness;

    public RunbookSearchService(VectorStore vectorStore, TriageProperties properties,
                                RunbookReadiness readiness) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.readiness = readiness;
    }

    public List<RunbookChunkView> search(String query, Integer requestedK) {
        readiness.requireReady();
        if (query == null || query.isBlank() || query.length() > 8_000) {
            throw new IllegalArgumentException("query must contain 1 to 8000 characters");
        }
        int topK = Math.max(1, Math.min(12,
                requestedK == null ? properties.retrieval().topK() : requestedK));
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(properties.retrieval().similarityThreshold())
                .build();
        return vectorStore.similaritySearch(request).stream().map(this::toView).toList();
    }

    private RunbookChunkView toView(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        String chunkId = requiredMetadata(metadata, "chunk_id", 768);
        String source = requiredMetadata(metadata, "source", 512);
        String heading = requiredMetadata(metadata, "heading", 256);
        String embeddingModel = requiredMetadata(metadata, "embedding_model", 256);
        if (!properties.retrieval().embeddingModel().equals(embeddingModel)) {
            throw new IllegalStateException("retrieved runbook uses a stale embedding model");
        }
        Object trusted = metadata.get("trusted");
        if (!(Boolean.TRUE.equals(trusted) || "true".equalsIgnoreCase(String.valueOf(trusted)))) {
            throw new IllegalStateException("retrieved runbook is not marked trusted");
        }
        String content = document.getText();
        if (content == null || content.isBlank()
                || content.length() > RunbookChunker.MAX_CHUNK_CHARACTERS) {
            throw new IllegalStateException("retrieved runbook content is missing or oversized");
        }
        double score = document.getScore() == null ? 0 : document.getScore();
        if (!Double.isFinite(score)) {
            throw new IllegalStateException("retrieved runbook score is not finite");
        }
        return new RunbookChunkView(
                chunkId, source, heading, content, score);
    }

    private static String requiredMetadata(Map<String, Object> metadata, String name, int maximum) {
        Object raw = metadata.get(name);
        if (!(raw instanceof String value) || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("retrieved runbook has invalid " + name + " metadata");
        }
        return value;
    }
}
