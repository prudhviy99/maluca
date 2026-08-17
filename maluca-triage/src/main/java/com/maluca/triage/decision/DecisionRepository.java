package com.maluca.triage.decision;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.decision.DecisionEvent;

@Repository
public class DecisionRepository {

    private static final TypeReference<Map<String, Double>> CONTRIBUTIONS = new TypeReference<>() { };
    private static final String INSERT = """
            INSERT INTO decisions (
                event_id, occurred_at, client_key, method, path, policy_name,
                policy_route, policy_mode, tier, computed_action, executed_action,
                score, reason, contributions, dry_run, trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;

    public DecisionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
    }

    public int insertBatch(List<DecisionEvent> events) {
        int[][] results = jdbc.batchUpdate(INSERT, events, events.size(), this::bind);
        int inserted = 0;
        for (int[] batch : results) {
            for (int result : batch) {
                if (result > 0 || result == PreparedStatement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }

    public List<DecisionEvent> find(DecisionQuery query) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, occurred_at, client_key, method, path, policy_name,
                       policy_route, policy_mode, tier, computed_action, executed_action,
                       score, reason, contributions, dry_run, trace_id
                  FROM decisions WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        append(sql, params, "policy_name = :policy", "policy", query.policyName());
        append(sql, params, "client_key = :client", "client", query.clientKey());
        append(sql, params, "computed_action = :action", "action", query.action());
        if (query.from() != null) {
            sql.append(" AND occurred_at >= :from");
            params.addValue("from", Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at <= :to");
            params.addValue("to", Timestamp.from(query.to()));
        }
        sql.append(" ORDER BY occurred_at DESC LIMIT :limit");
        params.addValue("limit", query.limit());
        return namedJdbc.query(sql.toString(), params, rowMapper());
    }

    public Map<String, Double> signalBreakdown(String policyName, Instant from, Instant to) {
        String sql = """
                SELECT signal.key, SUM((signal.value)::numeric)::double precision AS total
                  FROM decisions d
                  CROSS JOIN LATERAL jsonb_each_text(d.contributions) signal
                 WHERE d.policy_name = :policy
                   AND d.occurred_at >= :from AND d.occurred_at <= :to
                 GROUP BY signal.key ORDER BY total DESC LIMIT 64
                """;
        var params = new MapSqlParameterSource()
                .addValue("policy", policyName)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        Map<String, Double> values = new java.util.LinkedHashMap<>();
        namedJdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> values.put(rs.getString("key"), rs.getDouble("total")));
        return values;
    }

    public long purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM decisions WHERE occurred_at < ?", Timestamp.from(cutoff));
    }

    private void bind(PreparedStatement ps, DecisionEvent event) throws SQLException {
        ps.setObject(1, event.eventId());
        ps.setTimestamp(2, Timestamp.from(event.occurredAt()));
        ps.setString(3, event.clientKey());
        ps.setString(4, event.method());
        ps.setString(5, event.path());
        ps.setString(6, event.policyName());
        ps.setString(7, event.policyRoute());
        ps.setString(8, event.policyMode());
        ps.setString(9, event.tier());
        ps.setString(10, event.computedAction());
        ps.setString(11, event.executedAction());
        ps.setInt(12, event.score());
        ps.setString(13, event.reason());
        try {
            ps.setString(14, json.writeValueAsString(event.contributions()));
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot serialize decision contributions", e);
        }
        ps.setBoolean(15, event.dryRun());
        ps.setString(16, event.traceId());
    }

    private RowMapper<DecisionEvent> rowMapper() {
        return (rs, rowNum) -> new DecisionEvent(
                rs.getObject("event_id", java.util.UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("client_key"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getString("policy_name"),
                rs.getString("policy_route"),
                rs.getString("policy_mode"),
                rs.getString("tier"),
                rs.getString("computed_action"),
                rs.getString("executed_action"),
                rs.getInt("score"),
                rs.getString("reason"),
                readContributions(rs),
                rs.getBoolean("dry_run"),
                rs.getString("trace_id"));
    }

    private Map<String, Double> readContributions(ResultSet rs) throws SQLException {
        try {
            return json.readValue(rs.getString("contributions"), CONTRIBUTIONS);
        } catch (JsonProcessingException e) {
            throw new SQLException("Cannot parse stored contributions", e);
        }
    }

    private static void append(StringBuilder sql, MapSqlParameterSource params,
                               String condition, String name, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(condition);
            params.addValue(name, value);
        }
    }
}
