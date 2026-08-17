package com.maluca.triage.detection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.maluca.contracts.incident.CountedValue;
import com.maluca.contracts.incident.IncidentStats;

@Repository
public class DetectionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DetectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WindowAggregate> aggregate(Instant currentStart, Instant now,
                                           Instant baselineStart, Instant baselineEnd) {
        String sql = """
                WITH current_window AS (
                    SELECT policy_name, MAX(policy_route) AS policy_route,
                           COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE computed_action NOT IN ('ALLOW','OBSERVE')) AS mitigated,
                           COUNT(*) FILTER (WHERE computed_action IN ('CHALLENGE','BLOCK')) AS challenge_block,
                           COUNT(*) FILTER (WHERE reason LIKE 'redis_down%') AS redis_errors,
                           AVG(score)::double precision AS mean_score, MAX(score) AS max_score,
                           COUNT(DISTINCT client_key) AS distinct_clients,
                           COUNT(DISTINCT path) AS distinct_paths
                      FROM decisions
                     WHERE occurred_at >= :currentStart AND occurred_at <= :now
                     GROUP BY policy_name
                ), baseline AS (
                    SELECT policy_name, COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE computed_action NOT IN ('ALLOW','OBSERVE')) AS mitigated
                      FROM decisions
                     WHERE occurred_at >= :baselineStart AND occurred_at < :baselineEnd
                     GROUP BY policy_name
                )
                SELECT c.*, COALESCE(b.total, 0) AS baseline_total,
                       COALESCE(b.mitigated, 0) AS baseline_mitigated
                  FROM current_window c LEFT JOIN baseline b USING (policy_name)
                """;
        var params = new MapSqlParameterSource()
                .addValue("currentStart", Timestamp.from(currentStart))
                .addValue("now", Timestamp.from(now))
                .addValue("baselineStart", Timestamp.from(baselineStart))
                .addValue("baselineEnd", Timestamp.from(baselineEnd));
        return jdbc.query(sql, params, (rs, rowNum) -> new WindowAggregate(
                rs.getString("policy_name"), rs.getString("policy_route"),
                rs.getLong("total"), rs.getLong("mitigated"),
                rs.getLong("challenge_block"), rs.getLong("redis_errors"),
                rs.getDouble("mean_score"), rs.getInt("max_score"),
                rs.getLong("distinct_clients"), rs.getLong("distinct_paths"),
                rs.getLong("baseline_total"), rs.getLong("baseline_mitigated")));
    }

    public IncidentStats snapshot(WindowAggregate aggregate, Instant from, Instant to, int topLimit) {
        var params = new MapSqlParameterSource()
                .addValue("policy", aggregate.policyName())
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("limit", topLimit);
        Map<String, Double> contributions = contributionTotals(params);
        if (aggregate.redisErrors() > 0) {
            contributions = new LinkedHashMap<>(contributions);
            contributions.put("redis_errors", (double) aggregate.redisErrors());
        }
        return new IncidentStats(
                from, to, aggregate.total(), aggregate.mitigated(), aggregate.mitigationShare(),
                aggregate.baselineMitigationShare(), aggregate.meanScore(), aggregate.maxScore(),
                aggregate.distinctClients(), aggregate.distinctPaths(),
                countedMap("computed_action", params), contributions,
                countedList("client_key", params), countedList("path", params));
    }

    private Map<String, Long> countedMap(String column, MapSqlParameterSource params) {
        String sql = "SELECT " + column + " AS value, COUNT(*) AS count FROM decisions "
                + "WHERE policy_name=:policy AND occurred_at>=:from AND occurred_at<=:to "
                + "GROUP BY " + column + " ORDER BY count DESC";
        Map<String, Long> values = new LinkedHashMap<>();
        jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> values.put(rs.getString("value"), rs.getLong("count")));
        return values;
    }

    private List<CountedValue> countedList(String column, MapSqlParameterSource params) {
        String sql = "SELECT " + column + " AS value, COUNT(*) AS count FROM decisions "
                + "WHERE policy_name=:policy AND occurred_at>=:from AND occurred_at<=:to "
                + "GROUP BY " + column + " ORDER BY count DESC LIMIT :limit";
        return jdbc.query(sql, params,
                (rs, rowNum) -> new CountedValue(rs.getString("value"), rs.getLong("count")));
    }

    private Map<String, Double> contributionTotals(MapSqlParameterSource params) {
        String sql = """
                SELECT signal.key, SUM((signal.value)::numeric)::double precision AS total
                  FROM decisions d CROSS JOIN LATERAL jsonb_each_text(d.contributions) signal
                 WHERE policy_name=:policy AND occurred_at>=:from AND occurred_at<=:to
                 GROUP BY signal.key ORDER BY total DESC LIMIT :limit
                """;
        Map<String, Double> values = new LinkedHashMap<>();
        jdbc.query(sql, params, (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> values.put(rs.getString("key"), rs.getDouble("total")));
        return values;
    }
}
