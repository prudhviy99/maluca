package com.maluca.triage.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.contracts.triage.Citation;
import com.maluca.contracts.triage.EvidenceReference;
import com.maluca.contracts.triage.TriageReportView;
import com.maluca.contracts.triage.TriageResult;

@Repository
public class TriageReportRepository {

    private static final TypeReference<List<EvidenceReference>> EVIDENCE = new TypeReference<>() { };
    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() { };
    private static final TypeReference<List<RunbookChunkView>> RETRIEVAL = new TypeReference<>() { };
    private static final TypeReference<List<String>> ERRORS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TriageReportRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public TriageReportView save(UUID incidentId, String model, String promptVersion,
                                 TriageResult result, boolean valid, List<String> errors,
                                 String rawResponse, List<RunbookChunkView> retrievedChunks) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbc.update("""
                INSERT INTO triage_reports (
                    id, incident_id, created_at, model, prompt_version, classification,
                    confidence, summary, evidence, citations, retrieval_context, proposed_patch, valid,
                    validation_errors, raw_response
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                          CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb), ?)
                ON CONFLICT (incident_id) DO UPDATE SET
                    created_at=EXCLUDED.created_at, model=EXCLUDED.model,
                    prompt_version=EXCLUDED.prompt_version, classification=EXCLUDED.classification,
                    confidence=EXCLUDED.confidence, summary=EXCLUDED.summary,
                    evidence=EXCLUDED.evidence, citations=EXCLUDED.citations,
                    retrieval_context=EXCLUDED.retrieval_context,
                    proposed_patch=EXCLUDED.proposed_patch, valid=EXCLUDED.valid,
                    validation_errors=EXCLUDED.validation_errors, raw_response=EXCLUDED.raw_response
                """, id, incidentId, Timestamp.from(createdAt), model, promptVersion,
                result.classification().name(), result.confidence().name(), result.summary(),
                write(result.evidence()), write(result.citations()),
                write(retrievedChunks == null ? List.of() : retrievedChunks),
                result.proposedPatch() == null ? null : write(result.proposedPatch()),
                valid, write(errors), rawResponse == null ? "" : rawResponse);
        return findForIncident(incidentId).orElseThrow();
    }

    public Optional<TriageReportView> findForIncident(UUID incidentId) {
        return jdbc.query("SELECT * FROM triage_reports WHERE incident_id=?", rowMapper(), incidentId)
                .stream().findFirst();
    }

    private RowMapper<TriageReportView> rowMapper() {
        return (rs, rowNum) -> new TriageReportView(
                rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getString("model"),
                rs.getString("prompt_version"), Classification.valueOf(rs.getString("classification")),
                Confidence.valueOf(rs.getString("confidence")), rs.getString("summary"),
                read(rs.getString("evidence"), EVIDENCE), read(rs.getString("citations"), CITATIONS),
                read(rs.getString("retrieval_context"), RETRIEVAL),
                rs.getString("proposed_patch") == null ? null
                        : read(rs.getString("proposed_patch"), PolicyPatch.class),
                rs.getBoolean("valid"), read(rs.getString("validation_errors"), ERRORS));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize report", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read report", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read report", e);
        }
    }
}
