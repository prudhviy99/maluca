package com.maluca.triage.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.config.TriageProperties;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({ "rawtypes", "unchecked" })
class RunbookIngestionServiceTest {

    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";
    private static final String CURRENT_ID = "00000000-0000-0000-0000-000000000101";
    private static final String RETIRED_ID = "00000000-0000-0000-0000-000000000102";
    private static final String CURRENT_MARKDOWN = """
            # Current response

            ## Symptoms

            New trusted guidance.
            """;

    @Mock
    private ResourcePatternResolver resources;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private RunbookReadiness readiness;

    private TransactionStatus transactionStatus;

    @BeforeEach
    void configureTransaction() {
        transactionStatus = mock(TransactionStatus.class);
    }

    @Test
    void startupFailsClosedWhenLocationContainsNoMarkdownFiles() throws Exception {
        TriageProperties properties = properties(true);
        when(resources.getResources(properties.retrieval().runbookLocation()))
                .thenReturn(new Resource[] { resource("notes.txt", "not a runbook") });

        RunbookIngestionService service = service(properties);

        assertThatThrownBy(() -> service.run(mock(ApplicationArguments.class)))
                .isInstanceOf(RunbookIngestionService.EmptyRunbookCorpusException.class)
                .hasMessageContaining("zero Markdown files");
        verifyNoInteractions(embeddingModel, jdbc, vectorStore);
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void startupWithoutIngestionIsReadyOnlyWhenLastGoodCorpusExists() {
        TriageProperties properties = properties(false);
        when(jdbc.queryForMap(contains("count(*) AS total"),
                eq(properties.retrieval().embeddingModel())))
                .thenReturn(Map.of("total", 4L, "matching", 4L));

        service(properties).run(mock(ApplicationArguments.class));

        verify(readiness).ready(contains("chunks=4"));
        verifyNoInteractions(resources, embeddingModel, vectorStore);
    }

    @Test
    void transientEmbeddingFailureRetainsLastGoodReadiness() throws Exception {
        TriageProperties properties = properties(true);
        prepareReplacement(properties);
        org.mockito.Mockito.doThrow(new TransientAiException("ollama temporarily unavailable"))
                .when(vectorStore).add(anyList());
        when(jdbc.queryForMap(contains("count(*) AS total"),
                eq(properties.retrieval().embeddingModel())))
                .thenReturn(Map.of("total", 2L, "matching", 2L));

        service(properties).run(mock(ApplicationArguments.class));

        verify(transactionManager).rollback(transactionStatus);
        verify(readiness).ready(contains("last-good corpus retained; chunks=2"));
        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    void disabledIngestionRejectsLastGoodVectorsFromAnotherEmbeddingModel() {
        TriageProperties properties = properties(false);
        when(jdbc.queryForMap(contains("count(*) AS total"),
                eq(properties.retrieval().embeddingModel())))
                .thenReturn(Map.of("total", 4L, "matching", 0L));

        service(properties).run(mock(ApplicationArguments.class));

        verify(readiness).unavailable(contains("embedding model mismatch"));
        verifyNoInteractions(resources, embeddingModel, vectorStore);
    }

    @Test
    void permanentEmbeddingDimensionMismatchFailsStartup() throws Exception {
        TriageProperties properties = properties(true);
        when(resources.getResources(properties.retrieval().runbookLocation()))
                .thenReturn(new Resource[] { resource("current.md", CURRENT_MARKDOWN) });
        when(embeddingModel.dimensions()).thenReturn(7);

        assertThatThrownBy(() -> service(properties).run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match schema dimension");

        verify(readiness).unavailable("permanent ingestion failure");
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    void successfulReplacementUpsertsBeforeDeletingObsoleteChunks() throws Exception {
        TriageProperties properties = properties(false);
        prepareReplacement(properties);
        RunbookIngestionService service = service(properties);

        RunbookIngestionService.IngestionResult result = service.ingest();

        assertThat(result).isEqualTo(new RunbookIngestionService.IngestionResult(1, 1, 0, 1));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        assertThat(documents.getValue()).singleElement().satisfies(document ->
                assertThat(document.getId()).isEqualTo(CURRENT_ID));
        assertThat(documents.getValue().getFirst().getMetadata())
                .containsEntry("embedding_model", properties.retrieval().embeddingModel());

        InOrder order = inOrder(jdbc, vectorStore);
        order.verify(jdbc).queryForObject(eq(LOCK_SQL), any(RowMapper.class), anyLong());
        order.verify(jdbc).query(contains("FROM runbook_chunks"), any(ResultSetExtractor.class));
        order.verify(vectorStore).add(anyList());
        order.verify(vectorStore).delete(List.of(RETIRED_ID));
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(transactionStatus);
    }

    @Test
    void failedReplacementRollsBackAndNeverDeletesLastGoodChunks() throws Exception {
        TriageProperties properties = properties(false);
        prepareReplacement(properties);
        org.mockito.Mockito.doThrow(new IllegalStateException("embedding failed"))
                .when(vectorStore).add(anyList());
        RunbookIngestionService service = service(properties);

        assertThatThrownBy(service::ingest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding failed");

        verify(vectorStore, never()).delete(anyList());
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    private RunbookIngestionService service(TriageProperties properties) {
        return new RunbookIngestionService(resources, new RunbookChunker(), vectorStore,
                embeddingModel, jdbc, properties, transactionManager, readiness);
    }

    private void prepareReplacement(TriageProperties properties) throws Exception {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        when(resources.getResources(properties.retrieval().runbookLocation()))
                .thenReturn(new Resource[] { resource("current.md", CURRENT_MARKDOWN) });
        when(embeddingModel.dimensions()).thenReturn(8);
        when(jdbc.queryForObject(eq(LOCK_SQL), any(RowMapper.class), anyLong()))
                .thenReturn(Boolean.TRUE);

        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("id")).thenReturn(CURRENT_ID, RETIRED_ID);
        when(rows.getString("chunk_id")).thenReturn("current.md#symptoms", "retired.md#old");
        String currentSha = new RunbookChunker()
                .chunk("current.md", CURRENT_MARKDOWN).getFirst().sha256();
        // The text digest is current. A changed embedding-model identity alone
        // must still force an upsert with a fresh vector.
        when(rows.getString("sha256")).thenReturn(currentSha, "retired-sha");
        when(rows.getString("source")).thenReturn("current.md", "retired.md");
        when(rows.getString("embedding_model"))
                .thenReturn("old-embedding-model", "old-embedding-model");
        when(jdbc.query(contains("FROM runbook_chunks"), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<?> extractor = invocation.getArgument(1);
                    return extractor.extractData(rows);
                });
    }

    private static TriageProperties properties(boolean ingestOnStartup) {
        TriageProperties base = TriageTestFixtures.properties(Path.of("policies.yml"));
        TriageProperties.Retrieval retrieval = new TriageProperties.Retrieval(
                base.retrieval().topK(), base.retrieval().similarityThreshold(),
                "classpath*:test-runbooks/*", ingestOnStartup,
                base.retrieval().embeddingDimensions(), base.retrieval().embeddingModel());
        return new TriageProperties(base.security(), base.privacy(), base.ingest(),
                base.detection(), base.agent(), retrieval, base.retention(),
                base.policy(), base.upstreams());
    }

    private static Resource resource(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
