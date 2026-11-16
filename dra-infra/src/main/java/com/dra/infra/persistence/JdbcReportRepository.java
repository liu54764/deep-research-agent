package com.dra.infra.persistence;

import com.dra.domain.model.ReportRow;
import com.dra.domain.port.ReportRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class JdbcReportRepository implements ReportRepository {

    private final JdbcClient jdbc;

    public JdbcReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ReportRow save(String reportId, String taskId, int version, String markdown, String metaJson) {
        jdbc.sql("""
                INSERT INTO report (id, task_id, version, markdown, meta_json, created_at)
                VALUES (:id, :taskId, :version, :markdown, :metaJson, NOW(3))
                ON DUPLICATE KEY UPDATE
                    markdown = VALUES(markdown),
                    meta_json = VALUES(meta_json)
                """)
                .param("id", reportId)
                .param("taskId", taskId)
                .param("version", version)
                .param("markdown", markdown)
                .param("metaJson", metaJson)
                .update();
        return findLatest(taskId).orElseThrow(() -> new IllegalStateException("report was not persisted"));
    }

    @Override
    public Optional<ReportRow> findLatest(String taskId) {
        return jdbc.sql("""
                SELECT id, task_id, version, markdown, meta_json, created_at
                  FROM report
                 WHERE task_id = :taskId
                 ORDER BY version DESC
                 LIMIT 1
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new ReportRow(
                        rs.getString("id"),
                        rs.getString("task_id"),
                        rs.getInt("version"),
                        rs.getString("markdown"),
                        rs.getString("meta_json"),
                        rs.getTimestamp("created_at").getTime()))
                .optional();
    }
}
