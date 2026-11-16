package com.dra.infra.persistence;

import com.dra.domain.model.ReportConflict;
import com.dra.domain.model.ReportPosition;
import com.dra.domain.port.ConflictRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcConflictRepository implements ConflictRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcConflictRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String conflictId, String taskId, String groupKey, String summary, String positionsJson) {
        jdbc.sql("""
                INSERT INTO conflict
                    (id, task_id, claim_group_key, summary, positions_json, resolved, created_at)
                VALUES
                    (:id, :taskId, :groupKey, :summary, CAST(:positions AS JSON), 0, NOW(3))
                ON DUPLICATE KEY UPDATE
                    summary = VALUES(summary),
                    positions_json = VALUES(positions_json)
                """)
                .param("id", conflictId)
                .param("taskId", taskId)
                .param("groupKey", groupKey)
                .param("summary", summary)
                .param("positions", positionsJson)
                .update();
    }

    @Override
    public List<ReportConflict> findByTask(String taskId) {
        return jdbc.sql("""
                SELECT claim_group_key, summary, positions_json
                  FROM conflict
                 WHERE task_id = :taskId
                 ORDER BY created_at
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new ReportConflict(
                        rs.getString("claim_group_key"),
                        rs.getString("summary"),
                        parsePositions(rs.getString("positions_json"))))
                .list();
    }

    private List<ReportPosition> parsePositions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<ReportPosition> positions = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                String claimId = node.path("claimId").asText("");
                if (claimId.isBlank()) {
                    continue;
                }
                List<String> urls = new ArrayList<>();
                for (JsonNode url : node.path("urls")) {
                    String value = url.asText("");
                    if (!value.isBlank()) {
                        urls.add(value);
                    }
                }
                positions.add(new ReportPosition(
                        claimId,
                        node.path("statement").asText(""),
                        node.path("stance").asText(""),
                        urls));
            }
            return positions;
        } catch (Exception e) {
            return List.of();
        }
    }
}
