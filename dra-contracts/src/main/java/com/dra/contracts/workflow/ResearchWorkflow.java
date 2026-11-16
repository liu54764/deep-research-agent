package com.dra.contracts.workflow;

import com.dra.contracts.dto.ResearchProgress;
import com.dra.contracts.dto.ResearchRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ResearchWorkflow {

    String TASK_QUEUE = "drs-orchestration";

    @WorkflowMethod
    String run(ResearchRequest request);

    @SignalMethod
    void pause();

    @SignalMethod
    void resume();

    @QueryMethod
    ResearchProgress progress();
}
