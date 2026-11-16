package com.dra.infra.persistence;

import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.port.EvidenceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Repository
public class JdbcEvidenceRepository implements EvidenceRepository {

    private final JdbcClient jdbc;

    public JdbcEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(EvidenceRow evidence) {
        jdbc.sql("""
                INSERT IGNORE INTO evidence
                    (id, task_id, source_id, sub_question_id, `round`, quote, quote_hash,
                     anchor_json, origin, aspect, extract_version, created_at)
                VALUES
                    (:id, :taskId, :sourceId, :subQuestionId, :round, :quote, :quoteHash,
                     :anchorJson, :origin, :aspect, :extractVersion, NOW(3))
                """)
                .param("id", evidence.id())
                .param("taskId", evidence.taskId())
                .param("sourceId", evidence.sourceId())
                .param("subQuestionId", evidence.subQuestionId())
                .param("round", evidence.round())
                .param("quote", evidence.quote())
                .param("quoteHash", evidence.quoteHash())
                .param("anchorJson", evidence.anchorJson())
                .param("origin", evidence.origin())
                .param("aspect", evidence.aspect())
                .param("extractVersion", evidence.extractVersion())
                .update();
    }

    @Override
    public List<EvidenceView> findByIds(String taskId, List<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new HashSet<>(evidenceIds);
        return jdbc.sql("""
                SELECT e.id, e.task_id, e.source_id, e.sub_question_id, e.`round`,
                       e.quote, e.origin, s.url, s.title
                  FROM evidence e
                  JOIN source s ON s.id = e.source_id
                 WHERE e.task_id = :taskId
                 ORDER BY e.created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new EvidenceView(
                        rs.getString("id"),
                        rs.getString("task_id"),
                        rs.getString("source_id"),
                        rs.getString("sub_question_id"),
                        rs.getInt("round"),
                        rs.getString("quote"),
                        rs.getString("origin"),
                        rs.getString("url"),
                        rs.getString("title")))
                .list().stream()
                .filter(view -> wanted.contains(view.id()))
                .toList();
    }
}
