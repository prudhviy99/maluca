package com.maluca.triage.runbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

class RetrievalRegressionTest {

    private static VectorStore store;

    @BeforeAll
    static void buildOfflineIndex() throws Exception {
        store = SimpleVectorStore.builder(new ScenarioEmbeddingModel()).build();
        RunbookChunker chunker = new RunbookChunker();
        List<Document> documents = new ArrayList<>();
        for (String source : List.of("burst-flood.md", "distributed-flood.md", "path-scan.md",
                "credential-stuffing.md", "low-and-slow.md", "redis-degradation.md",
                "false-positive-wave.md")) {
            String markdown = new ClassPathResource("runbooks/" + source)
                    .getContentAsString(StandardCharsets.UTF_8);
            for (RunbookChunk chunk : chunker.chunk(source, markdown)) {
                documents.add(new Document(chunk.chunkId(), chunk.content(), Map.of(
                        "chunk_id", chunk.chunkId(), "source", source, "heading", chunk.heading())));
            }
        }
        store.add(documents);
    }

    @ParameterizedTest
    @CsvSource({
            "'BURST_FLOOD high burst_10s concentrated client',burst-flood.md",
            "'DISTRIBUTED_FLOOD many client identities aggregate volume',distributed-flood.md",
            "'PATH_SCAN distinct paths and fourxx enumeration',path-scan.md",
            "'CREDENTIAL_STUFFING sensitive login failures',credential-stuffing.md",
            "'LOW_AND_SLOW sustained low rate over time',low-and-slow.md",
            "'REDIS_DEGRADATION redis_down_fail_closed',redis-degradation.md",
            "'FALSE_POSITIVE_WAVE known good users after policy reload',false-positive-wave.md"
    })
    void expectedRunbookIsInTopThree(String brief, String expectedSource) {
        List<Document> results = store.similaritySearch(SearchRequest.builder()
                .query(brief).topK(3).similarityThresholdAll().build());
        assertThat(results).extracting(result -> String.valueOf(result.getMetadata().get("source")))
                .contains(expectedSource);
    }

    private static final class ScenarioEmbeddingModel implements EmbeddingModel {
        private static final List<String> MARKERS = List.of(
                "burst_flood", "distributed_flood", "path_scan", "credential_stuffing",
                "low_and_slow", "redis_degradation", "false_positive_wave");

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> values = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                values.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(values);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return MARKERS.size();
        }

        private static float[] vector(String value) {
            String normalized = value.toLowerCase().replace('-', '_').replace(' ', '_');
            float[] vector = new float[MARKERS.size()];
            for (int i = 0; i < MARKERS.size(); i++) {
                if (normalized.contains(MARKERS.get(i))) vector[i] = 1;
            }
            // Ensure nonzero vectors for section text that does not repeat a class name.
            if (java.util.Arrays.equals(vector, new float[MARKERS.size()])) vector[0] = .01f;
            return vector;
        }
    }
}
