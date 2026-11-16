package com.dra.worker.activity;

import com.dra.contracts.dto.PlanResult;
import com.dra.contracts.dto.RefineInput;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.Usage;
import com.dra.contracts.model.Depth;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.port.PlanRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanActivitiesImplTest {

    private static final String VALID = """
            ```json
            {"subQuestions":[
              {"title":"Approach","question":"What is the approach?","queries":["temporal workflow","durable execution"]},
              {"title":"Trade-offs","question":"What are the trade-offs?","queries":[]}
            ]}
            ```
            """;

    @Test
    void parsesValidPlanAndPersistsSubQuestions() {
        FakeLlm llm = new FakeLlm(VALID);
        FakePlanRepository repository = new FakePlanRepository();
        PlanActivitiesImpl activity = new PlanActivitiesImpl(
                llm, repository, new NoopUsageRepository(), new PromptLoader(new DefaultResourceLoader()),
                properties(), new ObjectMapper());

        PlanResult result = activity.create(context());

        assertEquals(2, result.subQuestions().size());
        assertEquals("What is the approach?", result.subQuestions().get(0).question());
        assertEquals(2, result.subQuestions().get(0).queries().size());
        // empty queries fall back to the question itself
        assertEquals(List.of("What are the trade-offs?"), result.subQuestions().get(1).queries());
        assertEquals(26, result.subQuestions().get(0).id().length());
        assertTrue(result.usage().totalTokens() > 0);
        assertEquals(1, repository.plans);
        assertEquals(2, repository.subQuestions.size());
    }

    @Test
    void fallsBackDeterministicallyWhenJsonIsInvalidTwice() {
        FakeLlm llm = new FakeLlm("not json", "still not json");
        FakePlanRepository repository = new FakePlanRepository();
        PlanActivitiesImpl activity = new PlanActivitiesImpl(
                llm, repository, new NoopUsageRepository(), new PromptLoader(new DefaultResourceLoader()),
                properties(), new ObjectMapper());

        PlanResult result = activity.create(context());

        assertTrue(result.degraded());
        assertTrue(result.warning().contains("fallback"));
        assertEquals(3, result.subQuestions().size());
        assertEquals(2, llm.calls);
        assertEquals(1, repository.plans);
    }

    private static TaskContext context() {
        return new TaskContext("task-1", "Temporal vs LangGraph", Depth.STANDARD, 2, 8, 40,
                System.currentTimeMillis() + 600_000, 500_000, 0, "auto", List.of(), List.of());
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setPlanModel("deepseek-chat");
        properties.setPlanTemperature(0.2);
        properties.setMaxTokens(2048);
        return properties;
    }

    private static final class FakeLlm implements LlmPort {
        private final Deque<String> responses = new ArrayDeque<>();
        private int calls;

        private FakeLlm(String... responses) {
            for (String response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            String response = responses.isEmpty() ? "not json" : responses.poll();
            return new LlmResponse(response, Usage.tokens(100, 50), request.model(), "stop");
        }
    }

    private static final class NoopUsageRepository implements UsageRepository {
        @Override
        public void record(String taskId, String activity, String profile, Usage usage) {
        }
    }

    private static final class FakePlanRepository implements PlanRepository {
        private int plans;
        private final List<SubQuestion> subQuestions = new ArrayList<>();

        @Override
        public void savePlan(String planId, String taskId, int version, List<SubQuestion> subQuestions,
                             String modelProfile, String promptVersion) {
            plans++;
            this.subQuestions.addAll(subQuestions);
        }

        @Override
        public void saveSubQuestions(String taskId, int planVersion, List<SubQuestion> subQuestions) {
        }
    }
}
