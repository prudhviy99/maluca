package com.maluca.triage.runbook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import com.maluca.triage.config.TriageProperties;

/** Checksum-aware, idempotent ingestion of the repository-owned runbooks. */
@Service
public class RunbookIngestionService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunbookIngestionService.class);
    private static final long INGESTION_LOCK_ID = 0x4d414c5543415242L;
    private static final String INGESTION_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";
    private static final int MAX_CORPUS_CHUNKS = 1_000;

    private final ResourcePatternResolver resources;
    private final RunbookChunker chunker;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final TriageProperties properties;
    private final TransactionTemplate transaction;
    private final RunbookReadiness readiness;

    public RunbookIngestionService(ResourcePatternResolver resources, RunbookChunker chunker,
                                   VectorStore vectorStore, EmbeddingModel embeddingModel,
                                   JdbcTemplate jdbc, TriageProperties properties,
                                   PlatformTransactionManager transactionManager,
                                   RunbookReadiness readiness) {
        this.resources = resources;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readiness = readiness;
        this.transaction.setName("runbook-ingestion");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.retrieval().ingestOnStartup()) {
            updateReadinessFromStoredCorpus("startup ingestion disabled");
            return;
        }
        try {
            IngestionResult result = ingest();
            log.info("runbooks_ingested discovered={} changed={} unchanged={} removed={}",
                    result.discovered(), result.changed(), result.unchanged(), result.removed());
        } catch (Exception e) {
            if (!isRetryableDependencyFailure(e)) {
                readiness.unavailable("permanent ingestion failure");
                log.error("runbook_ingestion_failed_closed location={} error={}",
                        properties.retrieval().runbookLocation(), e.toString());
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("runbook ingestion failed", e);
            }
            // Keep using a stored last-good corpus, if one exists. A fresh
            // database remains not-ready so the worker cannot burn claim attempts.
            updateReadinessFromStoredCorpus("dependency unavailable; last-good corpus retained");
            log.warn("runbook_ingestion_deferred error={}", e.toString());
        }
    }

    public synchronized IngestionResult ingest() throws IOException {
        try {
            List<RunbookChunk> chunks = loadCorpus();
            validateDimensions();
            IngestionResult result = Objects.requireNonNull(
                    transaction.execute(status -> ingestLocked(chunks)),
                    "runbook ingestion transaction returned no result");
            readiness.ready("trusted corpus available; chunks=" + result.discovered());
            return result;
        } catch (IOException | RuntimeException failure) {
            readiness.unavailable("ingestion attempt failed");
            throw failure;
        }
    }

    private List<RunbookChunk> loadCorpus() throws IOException {
        Resource[] found = resources.getResources(properties.retrieval().runbookLocation());
        List<RunbookChunk> chunks = new ArrayList<>();
        Set<String> chunkIds = new HashSet<>();
        int markdownFiles = 0;
        for (Resource resource : found) {
            String source = resource.getFilename();
            if (source == null || !source.toLowerCase(Locale.ROOT).endsWith(".md")) {
                continue;
            }
            markdownFiles++;
            long contentLength = resource.contentLength();
            if (contentLength > (long) RunbookChunker.MAX_MARKDOWN_CHARACTERS * 4) {
                throw new IllegalArgumentException("runbook resource is oversized: " + source);
            }
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            for (RunbookChunk chunk : chunker.chunk(source, markdown)) {
                if (!chunkIds.add(chunk.chunkId())) {
                    throw new IllegalArgumentException(
                            "configured runbook corpus contains duplicate chunk ID: "
                                    + chunk.chunkId());
                }
                chunks.add(chunk);
            }
            if (chunks.size() > MAX_CORPUS_CHUNKS) {
                throw new IllegalArgumentException(
                        "configured runbook corpus exceeds " + MAX_CORPUS_CHUNKS + " chunks");
            }
        }
        if (markdownFiles == 0) {
            throw new EmptyRunbookCorpusException(
                    "configured runbook corpus discovered zero Markdown files");
        }
        return List.copyOf(chunks);
    }

    private IngestionResult ingestLocked(List<RunbookChunk> chunks) {
        acquireClusterLock();

        Map<String, StoredChunk> existing = jdbc.query("""
                SELECT id::text, metadata->>'chunk_id' AS chunk_id,
                       metadata->>'sha256' AS sha256, metadata->>'source' AS source,
                       metadata->>'embedding_model' AS embedding_model
                  FROM runbook_chunks
                """, rs -> {
            Map<String, StoredChunk> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("chunk_id"), new StoredChunk(
                        rs.getString("id"), rs.getString("sha256"), rs.getString("source"),
                        rs.getString("embedding_model")));
            }
            return result;
        });

        int unchanged = 0;
        List<Document> additions = new ArrayList<>();
        Set<String> desired = new HashSet<>();
        for (RunbookChunk chunk : chunks) {
            desired.add(chunk.chunkId());
            StoredChunk stored = existing.get(chunk.chunkId());
            if (stored != null && chunk.sha256().equals(stored.sha256())
                    && properties.retrieval().embeddingModel().equals(stored.embeddingModel())) {
                unchanged++;
                continue;
            }
            String id = stableUuid(chunk.chunkId());
            if (stored != null) {
                id = stored.id();
            }
            additions.add(Document.builder()
                    .id(id)
                    .text(chunk.content())
                    .metadata(Map.of(
                            "chunk_id", chunk.chunkId(),
                            "source", chunk.source(),
                            "heading", chunk.heading(),
                            "sha256", chunk.sha256(),
                            "embedding_model", properties.retrieval().embeddingModel(),
                            "trusted", true))
                    .build());
        }

        List<String> obsoleteIds = new ArrayList<>();
        for (Map.Entry<String, StoredChunk> entry : existing.entrySet()) {
            // runbook_chunks is a dedicated, service-owned corpus. Leaving an
            // unknown/corrupt row would allow it to participate in trusted
            // retrieval, so every row absent from the desired corpus is stale.
            if (!desired.contains(entry.getKey())) {
                obsoleteIds.add(entry.getValue().id());
            }
        }

        // PgVectorStore uses INSERT ... ON CONFLICT (id) DO UPDATE. Embeddings and
        // replacement upserts must finish before obsolete last-good rows are removed.
        if (!additions.isEmpty()) {
            vectorStore.add(additions);
        }
        List<String> distinctObsoleteIds = obsoleteIds.stream().distinct().toList();
        if (!distinctObsoleteIds.isEmpty()) {
            vectorStore.delete(distinctObsoleteIds);
        }
        int removed = distinctObsoleteIds.size();
        return new IngestionResult(chunks.size(), additions.size(), unchanged, removed);
    }

    private void acquireClusterLock() {
        Boolean acquired = jdbc.queryForObject(INGESTION_LOCK_SQL,
                (resultSet, rowNumber) -> Boolean.TRUE, INGESTION_LOCK_ID);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("PostgreSQL did not acquire the runbook ingestion lock");
        }
    }

    private void validateDimensions() {
        int expected = properties.retrieval().embeddingDimensions();
        int actual = embeddingModel.dimensions();
        if (actual > 0 && actual != expected) {
            throw new IllegalStateException("embedding model dimension " + actual
                    + " does not match schema dimension " + expected);
        }
    }

    private void updateReadinessFromStoredCorpus(String reason) {
        try {
            Map<String, Object> counts = jdbc.queryForMap("""
                    SELECT count(*) AS total,
                           count(*) FILTER (
                               WHERE metadata->>'embedding_model' = ?
                                 AND metadata->>'trusted' = 'true'
                                 AND length(trim(metadata->>'chunk_id')) BETWEEN 1 AND 768
                                 AND length(trim(metadata->>'source')) BETWEEN 1 AND 512
                                 AND length(trim(metadata->>'heading')) BETWEEN 1 AND 256
                                 AND metadata->>'sha256' ~ '^[0-9a-f]{64}$'
                                 AND length(content) BETWEEN 1 AND 32000) AS matching
                      FROM runbook_chunks
                    """, properties.retrieval().embeddingModel());
            long total = number(counts.get("total"));
            long matching = number(counts.get("matching"));
            if (total > 0 && total == matching) {
                readiness.ready(reason + "; chunks=" + total
                        + "; embedding_model=" + properties.retrieval().embeddingModel());
            } else if (total > 0) {
                readiness.unavailable(reason
                        + "; stored corpus embedding model mismatch or invalid trust metadata");
            } else {
                readiness.unavailable(reason + "; no stored chunks");
            }
        } catch (RuntimeException databaseFailure) {
            readiness.unavailable(reason + "; corpus state unavailable");
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static boolean isRetryableDependencyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TransientAiException
                    || current instanceof ResourceAccessException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record StoredChunk(String id, String sha256, String source, String embeddingModel) {
    }

    public static final class EmptyRunbookCorpusException extends IllegalStateException {

        public EmptyRunbookCorpusException(String message) {
            super(message);
        }
    }

    public record IngestionResult(int discovered, int changed, int unchanged, int removed) {
    }
}
