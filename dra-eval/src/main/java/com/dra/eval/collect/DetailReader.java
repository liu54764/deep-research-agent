package com.dra.eval.collect;

import com.dra.domain.model.TaskRow;
import com.dra.domain.port.TaskRepository;
import com.dra.eval.support.EvalInfraException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only queries over the business schema. Kept here (not in dra-domain) on purpose:
 * these are evaluation-specific joins, and adding them to production ports would leak
 * eval concerns into the product.
 */
@Component
public class DetailReader {

    private final JdbcClient jdbc;
    private final TaskRepository taskRepository;

    public DetailReader(JdbcClient jdbc, TaskRepository taskRepository) {
        this.jdbc = jdbc;
        this.taskRepository = taskRepository;
    }

    public DbSnapshot load(String taskId) {
        TaskRow task;
        try {
            task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new EvalInfraException("task not found: " + taskId));
        } catch (EvalInfraException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EvalInfraException("cannot read task " + taskId + ": " + e.getMessage(), e);
        }

        List<DbSnapshot.DbClaim> claims = claims(taskId);
        List<DbSnapshot.DbCitation> citations = citations(taskId);
        List<DbSnapshot.DbSource> sources = sources(taskId);
        int evidenceCount = count("evidence", taskId);
        long[] usage = usage(taskId);
        return new DbSnapshot(task, claims, citations, sources, evidenceCount, usage[0], usage[1]);
    }

    private List<DbSnapshot.DbClaim> claims(String taskId) {
        return jdbc.sql("""
                SELECT id, section_key, statement, importance, status, confidence
                  FROM claim
                 WHERE task_id = :taskId
                 ORDER BY created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new DbSnapshot.DbClaim(
                        rs.getString("id"),
                        rs.getString("section_key"),
                        rs.getString("statement"),
                        rs.getInt("importance"),
                        rs.getString("status"),
                        decimal(rs.getBigDecimal("confidence"))))
                .list();
    }

    private List<DbSnapshot.DbCitation> citations(String taskId) {
        return jdbc.sql("""
                SELECT c.id AS claim_id, ci.evidence_id, e.source_id, e.quote, e.origin,
                       s.url, s.title
                  FROM claim c
                  JOIN citation ci ON ci.claim_id = c.id
                  JOIN evidence e ON e.id = ci.evidence_id
                  LEFT JOIN source s ON s.id = e.source_id
                 WHERE c.task_id = :taskId
                 ORDER BY ci.created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new DbSnapshot.DbCitation(
                        rs.getString("claim_id"),
                        rs.getString("evidence_id"),
                        rs.getString("source_id"),
                        rs.getString("url"),
                        rs.getString("title"),
                        rs.getString("quote"),
                        rs.getString("origin")))
                .list();
    }

    private List<DbSnapshot.DbSource> sources(String taskId) {
        return jdbc.sql("""
                SELECT id, url, canonical_url, fetch_status
                  FROM source
                 WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new DbSnapshot.DbSource(
                        rs.getString("id"),
                        rs.getString("url"),
                        rs.getString("canonical_url"),
                        rs.getString("fetch_status")))
                .list();
    }

    private int count(String table, String taskId) {
        // table is a compile-time literal at every call site, never user input.
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE task_id = :taskId")
                .param("taskId", taskId)
                .query(Integer.class)
                .single();
    }

    private long[] usage(String taskId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                       COALESCE(SUM(cost_micros), 0) AS cost
                  FROM usage_record
                 WHERE task_id = :taskId
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new long[]{rs.getLong("tokens"), rs.getLong("cost")})
                .single();
    }

    private static double decimal(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }
}
