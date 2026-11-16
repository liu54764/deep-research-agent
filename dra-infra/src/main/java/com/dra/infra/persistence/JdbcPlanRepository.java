package com.dra.infra.persistence;

import com.dra.contracts.dto.SubQuestion;
import com.dra.domain.port.PlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class JdbcPlanRepository implements PlanRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPlanRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void savePlan(String planId, String taskId, int version, List<SubQuestion> subQuestions,
                         String modelProfile, String promptVersion) {
        jdbc.sql("""
                INSERT INTO task_plan
                    (id, task_id, version, outline_json, stop_conditions_json, model_profile,
                     prompt_version, created_at)
                VALUES
                    (:id, :taskId, :version, :outlineJson, NULL, :modelProfile, :promptVersion, NOW(3))
                ON DUPLICATE KEY UPDATE
                    outline_json = VALUES(outline_json),
                    model_profile = VALUES(model_profile),
                    prompt_version = VALUES(prompt_version)
                """)
                .param("id", planId)
                .param("taskId", taskId)
                .param("version", version)
                .param("outlineJson", write(subQuestions))
                .param("modelProfile", modelProfile)
                .param("promptVersion", promptVersion)
                .update();
        saveSubQuestions(taskId, version, subQuestions);
    }

    @Override
    public void saveSubQuestions(String taskId, int planVersion, List<SubQuestion> subQuestions) {
        for (SubQuestion subQuestion : subQuestions) {
            jdbc.sql("""
                    INSERT INTO sub_question
                        (id, task_id, plan_version, seq, title, question, queries_json, status,
                         coverage_score, missing_aspects)
                    VALUES
                        (:id, :taskId, :planVersion, :seq, :title, :question, :queriesJson, :status,
                         NULL, NULL)
                    ON DUPLICATE KEY UPDATE
                        title = VALUES(title),
                        question = VALUES(question),
                        queries_json = VALUES(queries_json),
                        status = VALUES(status)
                    """)
                    .param("id", subQuestion.id())
                    .param("taskId", taskId)
                    .param("planVersion", planVersion)
                    .param("seq", subQuestion.seq())
                    .param("title", subQuestion.title())
                    .param("question", subQuestion.question())
                    .param("queriesJson", write(subQuestion.queries()))
                    .param("status", subQuestion.status())
                    .update();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize plan json", e);
        }
    }
}
