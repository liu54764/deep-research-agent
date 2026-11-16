package com.dra.api.service;

import com.dra.contracts.dto.ResearchRequest;
import com.dra.contracts.workflow.ResearchWorkflow;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class WorkflowGateway {

    private final WorkflowClient workflowClient;

    public WorkflowGateway(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public static String workflowId(String taskId) {
        return "research/" + taskId;
    }

    public WorkflowExecution start(String taskId, ResearchRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(ResearchWorkflow.TASK_QUEUE)
                .setWorkflowId(workflowId(taskId))
                .setWorkflowExecutionTimeout(Duration.ofMinutes(45))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        ResearchWorkflow workflow = workflowClient.newWorkflowStub(ResearchWorkflow.class, options);
        return WorkflowClient.start(workflow::run, request);
    }

    public void pause(String taskId) {
        untyped(taskId).signal("pause");
    }

    public void resume(String taskId) {
        untyped(taskId).signal("resume");
    }

    public void cancel(String taskId) {
        untyped(taskId).cancel();
    }

    private WorkflowStub untyped(String taskId) {
        return workflowClient.newUntypedWorkflowStub(workflowId(taskId));
    }
}
