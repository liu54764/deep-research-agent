package com.dra.infra.persistence;

import com.dra.contracts.model.Depth;
import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.TaskRow;
import com.dra.domain.port.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTaskRepository implements TaskRepository {

    private final JdbcClient jdbc;

    public JdbcTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(TaskRow row) {
        String sql = """
                INSERT INTO research_task
                    (id, idempotency_key, request_hash, question, status, depth,
                     budget_tokens, budget_cost_micros, used_tokens, used_cost_micros, created_at)
                VALUES
                    (:id, :idempotencyKey, :requestHash, :question, :status, :depth,
                     :budgetTokens, :budgetCostMicros, :usedTokens, :usedCostMicros, :createdAt)
                """;
        try {
            int updated = jdbc.sql(sql)
                    .param("id", row.id())
                    .param("idempotencyKey", row.idempotencyKey())
                    .param("requestHash", row.requestHash())
                    .param("question", row.question())
                    .param("status", row.status().name())
                    .param("depth", row.depth().name())
                    .param("budgetTokens", row.budgetTokens())
                    .param("budgetCostMicros", row.budgetCostMicros())
                    .param("usedTokens", row.usedTokens())
                    .param("usedCostMicros", row.usedCostMicros())
                    .param("createdAt", new Timestamp(row.createdAtMs()))
                    .update();
            return updated == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<TaskRow> findById(String id) {
        return jdbc.sql("SELECT * FROM research_task WHERE id = :id")
                .param("id", id)
                .query(JdbcTaskRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<TaskRow> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT * FROM research_task WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(JdbcTaskRepository::mapRow)
                .optional();
    }

    @Override
    public void markRunning(String id, String workflowId, String runId) {
        jdbc.sql("""
                UPDATE research_task
                   SET status = 'RUNNING',
                       started_at = COALESCE(started_at, NOW(3))
                 WHERE id = :id
                """)
                .param("id", id)
                .update();
    }

    @Override
    public void attachWorkflow(String id, String workflowId, String runId) {
        jdbc.sql("""
                UPDATE research_task
                   SET workflow_id = :workflowId,
                       run_id = :runId
                 WHERE id = :id
                """)
                .param("workflowId", workflowId)
                .param("runId", runId)
                .param("id", id)
                .update();
    }

    @Override
    public void markTerminal(String id, TaskStatus status, String stopReason, String reportId,
                             long usedTokens, long usedCostMicros) {
        jdbc.sql("""
                UPDATE research_task
                   SET status = :status,
                       stop_reason = :stopReason,
                       report_id = :reportId,
                       used_tokens = :usedTokens,
                       used_cost_micros = :usedCostMicros,
                       finished_at = NOW(3)
                 WHERE id = :id
                """)
                .param("status", status.name())
                .param("stopReason", stopReason)
                .param("reportId", reportId)
                .param("usedTokens", usedTokens)
                .param("usedCostMicros", usedCostMicros)
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(String id, String errorCode, String errorMessage,
                           long usedTokens, long usedCostMicros) {
        String safeMessage = errorMessage == null ? null
                : errorMessage.substring(0, Math.min(errorMessage.length(), 1000));
        jdbc.sql("""
                UPDATE research_task
                   SET status = 'FAILED',
                       error_code = :errorCode,
                       error_msg = :errorMessage,
                       used_tokens = :usedTokens,
                       used_cost_micros = :usedCostMicros,
                       finished_at = NOW(3)
                 WHERE id = :id
                """)
                .param("errorCode", errorCode)
                .param("errorMessage", safeMessage)
                .param("usedTokens", usedTokens)
                .param("usedCostMicros", usedCostMicros)
                .param("id", id)
                .update();
    }

    @Override
    public List<TaskRow> findRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("SELECT * FROM research_task ORDER BY created_at DESC LIMIT :limit")
                .param("limit", safeLimit)
                .query(JdbcTaskRepository::mapRow)
                .list();
    }

    private static TaskRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new TaskRow(
                rs.getString("id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("question"),
                TaskStatus.valueOf(rs.getString("status")),
                Depth.valueOf(rs.getString("depth")),
                rs.getLong("budget_tokens"),
                rs.getLong("budget_cost_micros"),
                rs.getLong("used_tokens"),
                rs.getLong("used_cost_micros"),
                rs.getString("workflow_id"),
                rs.getString("run_id"),
                rs.getString("stop_reason"),
                rs.getString("error_code"),
                rs.getString("error_msg"),
                rs.getString("report_id"),
                rs.getTimestamp("created_at").getTime(),
                started == null ? null : started.getTime(),
                finished == null ? null : finished.getTime());
    }
}
