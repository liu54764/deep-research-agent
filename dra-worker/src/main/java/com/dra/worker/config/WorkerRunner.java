package com.dra.worker.config;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.workflow.ResearchWorkflow;
import com.dra.worker.activity.ActivityFailureWrapper;
import com.dra.worker.workflow.ResearchWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts one WorkerFactory and registers workflows/activities on separate queues.
 * Roles let compose run worker-io (orchestration+io) and worker-llm (llm) independently.
 */
@Component
public class WorkerRunner implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);
    private static final String IO_QUEUE = "drs-io";
    private static final String LLM_QUEUE = "drs-llm";

    private final WorkflowClient workflowClient;
    private final WorkerProperties properties;
    private final ResearchActivities.Task taskActivities;
    private final ResearchActivities.Events eventActivities;
    private final ResearchActivities.Plan planActivities;
    private final ResearchActivities.Search searchActivities;
    private final ResearchActivities.Fetch fetchActivities;
    private final ResearchActivities.Extract extractActivities;
    private final ResearchActivities.Coverage coverageActivities;
    private final ResearchActivities.Claim claimActivities;
    private final ResearchActivities.Verify verifyActivities;
    private final ResearchActivities.Conflict conflictActivities;
    private final ResearchActivities.Report reportActivities;

    private WorkerFactory factory;

    public WorkerRunner(WorkflowClient workflowClient,
                        WorkerProperties properties,
                        ResearchActivities.Task taskActivities,
                        ResearchActivities.Events eventActivities,
                        ResearchActivities.Plan planActivities,
                        ResearchActivities.Search searchActivities,
                        ResearchActivities.Fetch fetchActivities,
                        ResearchActivities.Extract extractActivities,
                        ResearchActivities.Coverage coverageActivities,
                        ResearchActivities.Claim claimActivities,
                        ResearchActivities.Verify verifyActivities,
                        ResearchActivities.Conflict conflictActivities,
                        ResearchActivities.Report reportActivities) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.taskActivities = taskActivities;
        this.eventActivities = eventActivities;
        this.planActivities = planActivities;
        this.searchActivities = searchActivities;
        this.fetchActivities = fetchActivities;
        this.extractActivities = extractActivities;
        this.coverageActivities = coverageActivities;
        this.claimActivities = claimActivities;
        this.verifyActivities = verifyActivities;
        this.conflictActivities = conflictActivities;
        this.reportActivities = reportActivities;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> roles = properties.getRoles();
        factory = WorkerFactory.newInstance(workflowClient);

        if (roles.contains("orchestration")) {
            Worker orchestration = factory.newWorker(ResearchWorkflow.TASK_QUEUE);
            orchestration.registerWorkflowImplementationTypes(ResearchWorkflowImpl.class);
        }
        if (roles.contains("io")) {
            Worker io = factory.newWorker(IO_QUEUE, WorkerOptions.newBuilder()
                    .setMaxConcurrentActivityExecutionSize(properties.getIoConcurrency())
                    .build());
            io.registerActivitiesImplementations(
                    ActivityFailureWrapper.wrap(ResearchActivities.Task.class, taskActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Events.class, eventActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Search.class, searchActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Fetch.class, fetchActivities));
        }
        if (roles.contains("llm")) {
            Worker llm = factory.newWorker(LLM_QUEUE, WorkerOptions.newBuilder()
                    .setMaxConcurrentActivityExecutionSize(properties.getLlmConcurrency())
                    .build());
            llm.registerActivitiesImplementations(
                    ActivityFailureWrapper.wrap(ResearchActivities.Plan.class, planActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Extract.class, extractActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Coverage.class, coverageActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Claim.class, claimActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Verify.class, verifyActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Conflict.class, conflictActivities),
                    ActivityFailureWrapper.wrap(ResearchActivities.Report.class, reportActivities));
        }

        factory.start();
        log.info("Temporal workers started with roles={}", roles);
    }

    @Override
    public void destroy() throws Exception {
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
