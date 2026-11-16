package com.dra.infra.persistence;

import com.dra.domain.model.ReportBundle;
import com.dra.domain.model.ReportCitation;
import com.dra.domain.model.ReportClaim;
import com.dra.domain.model.ReportConflict;
import com.dra.domain.model.ReportSection;
import com.dra.domain.model.ReportStats;
import com.dra.domain.port.ConflictRepository;
import com.dra.domain.port.ReportDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads everything the report needs by task ID. Facts come from persisted rows only:
 * the workflow passes IDs around, the report reads the tables.
 */
@Repository
public class JdbcReportDataRepository implements ReportDataRepository {

    private final JdbcClient jdbc;
    private final ConflictRepository conflictRepository;
    private final ObjectMapper objectMapper;

    public JdbcReportDataRepository(JdbcClient jdbc, ConflictRepository conflictRepository,
                                    ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.conflictRepository = conflictRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportBundle load(String taskId, String stopReason, List<String> warnings) {
        TaskMeta task = loadTask(taskId);
        List<ReportSection> sections = loadSections(taskId);
        List<ReportClaim> claims = loadClaims(taskId);
        List<ReportConflict> conflicts = conflictRepository.findByTask(taskId);
        ReportStats stats = loadStats(taskId, claims, task);
        return new ReportBundle(
                taskId,
                task.question(),
                task.depth(),
                stopReason == null ? task.stopReason() : stopReason,
                warnings,
                sections,
                claims,
                conflicts,
                stats);
    }

    private TaskMeta loadTask(String taskId) {
        return jdbc.sql("""
                SELECT question, depth, stop_reason, used_tokens, used_cost_micros,
                       created_at, started_at, finished_at
                  FROM research_task
                 WHERE id = :id
                """)
                .param("id", taskId)
                .query((rs, rowNum) -> {
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp started = rs.getTimestamp("started_at");
                    Timestamp finished = rs.getTimestamp("finished_at");
                    Timestamp from = started != null ? started : created;
                    // The report is generated just before the task is finalized, so
                    // finished_at may still be null: fall back to "now".
                    long end = finished != null ? finished.getTime() : System.currentTimeMillis();
                    long duration = from != null ? Math.max(0, end - from.getTime()) : 0L;
                    return new TaskMeta(
                            rs.getString("question"),
                            rs.getString("depth"),
                            rs.getString("stop_reason"),
                            rs.getLong("used_tokens"),
                            rs.getLong("used_cost_micros"),
                            duration);
                })
                .optional()
                .orElse(new TaskMeta("", "", null, 0, 0, 0));
    }

    private List<ReportSection> loadSections(String taskId) {
        return jdbc.sql("""
                SELECT id, seq, title, question, status, coverage_score
                  FROM sub_question
                 WHERE task_id = :taskId
                 ORDER BY seq
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new ReportSection(
                        rs.getString("id"),
                        rs.getInt("seq"),
                        rs.getString("title"),
                        rs.getString("question"),
                        rs.getString("status"),
                        rs.getBigDecimal("coverage_score") == null
                                ? 0.0
                                : rs.getBigDecimal("coverage_score").doubleValue()))
                .list();
    }

    private List<ReportClaim> loadClaims(String taskId) {
        Map<String, List<ReportCitation>> citations = loadCitations(taskId);
        return jdbc.sql("""
                SELECT id, section_key, statement, importance, status, confidence
                  FROM claim
                 WHERE task_id = :taskId
                 ORDER BY importance DESC, created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> {
                    String claimId = rs.getString("id");
                    return new ReportClaim(
                            claimId,
                            rs.getString("section_key"),
                            rs.getString("statement"),
                            rs.getInt("importance"),
                            rs.getString("status"),
                            rs.getBigDecimal("confidence") == null
                                    ? 0.0
                                    : rs.getBigDecimal("confidence").doubleValue(),
                            citations.getOrDefault(claimId, List.of()));
                })
                .list();
    }

    private Map<String, List<ReportCitation>> loadCitations(String taskId) {
        List<CitationRow> rows = jdbc.sql("""
                SELECT ci.claim_id, e.id AS evidence_id, e.source_id, e.quote, e.anchor_json,
                       s.url, s.title, s.domain
                  FROM citation ci
                  JOIN evidence e ON e.id = ci.evidence_id
                  JOIN claim c ON c.id = ci.claim_id
                  LEFT JOIN source s ON s.id = e.source_id
                 WHERE c.task_id = :taskId
                 ORDER BY ci.created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> {
                    int[] anchor = parseAnchor(rs.getString("anchor_json"));
                    return new CitationRow(rs.getString("claim_id"), new ReportCitation(
                            rs.getString("evidence_id"),
                            rs.getString("source_id"),
                            rs.getString("url"),
                            rs.getString("title"),
                            rs.getString("domain"),
                            rs.getString("quote"),
                            anchor[0],
                            anchor[1]));
                })
                .list();
        Map<String, List<ReportCitation>> byClaim = new LinkedHashMap<>();
        for (CitationRow row : rows) {
            byClaim.computeIfAbsent(row.claimId(), k -> new ArrayList<>()).add(row.citation());
        }
        return byClaim;
    }

    private ReportStats loadStats(String taskId, List<ReportClaim> claims, TaskMeta task) {
        int[] sourceCounts = jdbc.sql("""
                SELECT SUM(CASE WHEN fetch_status = 'OK' THEN 1 ELSE 0 END) AS ok_count,
                       SUM(CASE WHEN fetch_status = 'OK' THEN 0 ELSE 1 END) AS failed_count
                  FROM source
                 WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new int[]{rs.getInt("ok_count"), rs.getInt("failed_count")})
                .optional()
                .orElse(new int[]{0, 0});
        int evidenceCount = jdbc.sql("SELECT COUNT(*) FROM evidence WHERE task_id = :taskId")
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
        long verified = claims.stream().filter(ReportClaim::renderable).count();
        long[] usage = jdbc.sql("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micros), 0) AS cost
                  FROM usage_record
                 WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new long[]{rs.getLong("tokens"), rs.getLong("cost")})
                .optional()
                .orElse(new long[]{0, 0});
        return new ReportStats(
                sourceCounts[0],
                sourceCounts[1],
                evidenceCount,
                claims.size(),
                (int) verified,
                Math.max(task.tokens(), usage[0]),
                Math.max(task.costMicros(), usage[1]),
                task.durationMs());
    }

    private int[] parseAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.isBlank()) {
            return new int[]{-1, -1};
        }
        try {
            JsonNode node = objectMapper.readTree(anchorJson);
            return new int[]{node.path("start").asInt(-1), node.path("end").asInt(-1)};
        } catch (Exception e) {
            return new int[]{-1, -1};
        }
    }

    private record TaskMeta(String question, String depth, String stopReason, long tokens,
                            long costMicros, long durationMs) {
    }

    private record CitationRow(String claimId, ReportCitation citation) {
    }
}
