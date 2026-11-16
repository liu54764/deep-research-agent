package com.dra.infra.persistence;

import com.dra.domain.model.ClaimRow;
import com.dra.domain.port.ClaimRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcClaimRepository implements ClaimRepository {

    private final JdbcClient jdbc;

    public JdbcClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ClaimRow claim) {
        jdbc.sql("""
                INSERT INTO claim
                    (id, task_id, section_key, statement, importance, status, confidence, created_at)
                VALUES
                    (:id, :taskId, :sectionKey, :statement, :importance, :status, :confidence, NOW(3))
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    confidence = VALUES(confidence)
                """)
                .param("id", claim.id())
                .param("taskId", claim.taskId())
                .param("sectionKey", claim.sectionKey())
                .param("statement", claim.statement())
                .param("importance", claim.importance())
                .param("status", claim.status())
                .param("confidence", claim.confidence())
                .update();
    }

    @Override
    public Optional<ClaimRow> findById(String claimId) {
        return jdbc.sql("""
                SELECT id, task_id, section_key, statement, importance, status, confidence
                  FROM claim
                 WHERE id = :id
                """)
                .param("id", claimId)
                .query((rs, rowNum) -> new ClaimRow(
                        rs.getString("id"),
                        rs.getString("task_id"),
                        rs.getString("section_key"),
                        rs.getString("statement"),
                        rs.getInt("importance"),
                        rs.getString("status"),
                        rs.getBigDecimal("confidence") == null ? 0 : rs.getBigDecimal("confidence").doubleValue()))
                .optional();
    }

    @Override
    public void updateConfidence(String claimId, double confidence) {
        jdbc.sql("UPDATE claim SET confidence = :confidence WHERE id = :id")
                .param("confidence", Math.max(0.0, Math.min(1.0, confidence)))
                .param("id", claimId)
                .update();
    }
}
