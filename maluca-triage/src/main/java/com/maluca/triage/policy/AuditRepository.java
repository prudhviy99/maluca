package com.maluca.triage.policy;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID incidentId, String actor, String action, Map<String, ?> details) {
        try {
            jdbc.update("""
                    INSERT INTO audit_events (incident_id, actor, action, details)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    """, incidentId, actor, action, json.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize audit details", e);
        }
    }
}
