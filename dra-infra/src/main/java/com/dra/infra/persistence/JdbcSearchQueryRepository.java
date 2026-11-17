package com.dra.infra.persistence;

import com.dra.domain.model.SearchQueryRow;
import com.dra.domain.port.SearchQueryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSearchQueryRepository implements SearchQueryRepository {

    private final JdbcClient jdbc;

    public JdbcSearchQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(SearchQueryRow row) {
        jdbc.sql("""
                INSERT INTO search_query
                    (id, task_id, sub_question_id, `round`, provider, query_text, query_hash,
                     status, result_count, latency_ms, cost_micros, created_at)
                VALUES
                    (:id, :taskId, :subQuestionId, :round, :provider, :queryText, :queryHash,
                     :status, :resultCount, :latencyMs, :costMicros, NOW(3))
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    result_count = VALUES(result_count),
                    latency_ms = VALUES(latency_ms),
                    cost_micros = VALUES(cost_micros)
                """)
                .param("id", row.id())
                .param("taskId", row.taskId())
                .param("subQuestionId", row.subQuestionId())
                .param("round", row.round())
                .param("provider", row.provider())
                .param("queryText", row.queryText())
                .param("queryHash", row.queryHash())
                .param("status", row.status())
                .param("resultCount", row.resultCount())
                .param("latencyMs", row.latencyMs())
                .param("costMicros", row.costMicros())
                .update();
    }
}
