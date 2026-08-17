package com.maluca.triage.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.maluca.triage.TriageTestFixtures;

class RunbookSearchServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RunbookReadiness readiness = mock(RunbookReadiness.class);
    private final com.maluca.triage.config.TriageProperties properties =
            TriageTestFixtures.properties(Path.of("policies.yml"));
    private final RunbookSearchService search =
            new RunbookSearchService(vectorStore, properties, readiness);

    @Test
    void mapsOnlyTrustedCurrentModelMetadata() {
        Document document = Document.builder()
                .id("00000000-0000-0000-0000-000000000101")
                .text("# Burst flood\n\n## Confirm\n\nConfirm bounded evidence.")
                .metadata(Map.of(
                        "chunk_id", "burst-flood.md#confirm",
                        "source", "burst-flood.md",
                        "heading", "Confirm",
                        "embedding_model", properties.retrieval().embeddingModel(),
                        "trusted", true))
                .score(.91)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        var result = search.search("burst activity", 1);

        assertThat(result).singleElement().satisfies(chunk -> {
            assertThat(chunk.chunkId()).isEqualTo("burst-flood.md#confirm");
            assertThat(chunk.source()).isEqualTo("burst-flood.md");
            assertThat(chunk.similarity()).isEqualTo(.91);
        });
    }

    @Test
    void failsClosedForStaleOrUntrustedRetrievedRows() {
        Document stale = Document.builder()
                .id("00000000-0000-0000-0000-000000000102")
                .text("stale guidance")
                .metadata(Map.of(
                        "chunk_id", "stale.md#confirm",
                        "source", "stale.md",
                        "heading", "Confirm",
                        "embedding_model", "different-model",
                        "trusted", true))
                .score(.9)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(stale));

        assertThatThrownBy(() -> search.search("query", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale embedding model");
    }
}
