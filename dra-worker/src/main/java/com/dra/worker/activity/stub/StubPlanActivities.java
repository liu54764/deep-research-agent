package com.dra.worker.activity.stub;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.PlanResult;
import com.dra.contracts.dto.RefineInput;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.Usage;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Deterministic planner used until Spring AI + gateway wiring lands in M6. */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubPlanActivities implements ResearchActivities.Plan {

    private final WorkerProperties properties;

    public StubPlanActivities(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public PlanResult create(TaskContext context) {
        StubSupport.sleep(properties.getPlanSleepMs());
        int count = Math.max(1, Math.min(context.depth().subQuestions(), 4));
        List<SubQuestion> subQuestions = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            subQuestions.add(new SubQuestion(
                    Ids.ulid(),
                    i,
                    "Sub-question " + i,
                    context.question() + " — aspect " + i,
                    List.of(context.question() + " aspect " + i, "aspect " + i + " details"),
                    "OPEN"));
        }
        return new PlanResult(subQuestions, Usage.tokens(1_200, 600), "stub.plan.v1");
    }

    @Override
    public PlanResult refine(RefineInput input) {
        StubSupport.sleep(properties.getPlanSleepMs());
        return new PlanResult(input.subQuestions(), Usage.tokens(300, 150), "stub.plan.v1");
    }
}
