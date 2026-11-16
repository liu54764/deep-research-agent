package com.dra.infra.persistence;

import com.dra.contracts.dto.Usage;
import com.dra.domain.port.UsageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsageRepository implements UsageRepository {

    private final JdbcClient jdbc;

    public JdbcUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String taskId, String activity, String profile, Usage usage) {
        if (usage == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO usage_record
                    (task_id, activity, model_profile, prompt_tokens, completion_tokens, cost_micros, created_at)
                VALUES
                    (:taskId, :activity, :profile, :promptTokens, :completionTokens, :costMicros, NOW(3))
                """)
                .param("taskId", taskId)
                .param("activity", activity)
                .param("profile", profile)
                .param("promptTokens", usage.promptTokens())
                .param("completionTokens", usage.completionTokens())
                .param("costMicros", usage.costMicros())
                .update();
    }
}
