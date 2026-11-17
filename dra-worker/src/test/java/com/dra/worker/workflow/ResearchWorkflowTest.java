package com.dra.worker.workflow;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ClaimDraft;
import com.dra.contracts.dto.ConflictInput;
import com.dra.contracts.dto.ConflictReport;
import com.dra.contracts.dto.CoverageInput;
import com.dra.contracts.dto.CoverageReport;
import com.dra.contracts.dto.ExtractInput;
import com.dra.contracts.dto.ExtractResult;
import com.dra.contracts.dto.FetchInput;
import com.dra.contracts.dto.PlanResult;
import com.dra.contracts.dto.RefineInput;
import com.dra.contracts.dto.ReportInput;
import com.dra.contracts.dto.ReportRef;
import com.dra.contracts.dto.ResearchProgress;
import com.dra.contracts.dto.ResearchRequest;
import com.dra.contracts.dto.ResearchResult;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.SourceRef;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.SubQuestionCoverage;
import com.dra.contracts.dto.SynthesizeInput;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.dto.Usage;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import com.dra.contracts.model.Depth;
import com.dra.contracts.workflow.ResearchWorkflow;
import com.dra.worker.activity.ActivityFailureWrapper;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchWorkflowTest {

    private TestWorkflowEnvironment environment;

    private final TaskStub taskStub = new TaskStub();
    private final ClaimStub claimStub = new ClaimStub();
    private final SearchStub searchStub = new SearchStub();

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();

        Worker orchestration = environment.newWorker(ResearchWorkflow.TASK_QUEUE);
        orchestration.registerWorkflowImplementationTypes(ResearchWorkflowImpl.class);

        Worker io = environment.newWorker("drs-io");
        io.registerActivitiesImplementations(
                ActivityFailureWrapper.wrap(ResearchActivities.Task.class, taskStub),
                ActivityFailureWrapper.wrap(ResearchActivities.Events.class, new EventStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Search.class, searchStub),
                ActivityFailureWrapper.wrap(ResearchActivities.Fetch.class, new FetchStub()));

        Worker llm = environment.newWorker("drs-llm");
        llm.registerActivitiesImplementations(
                ActivityFailureWrapper.wrap(ResearchActivities.Plan.class, new PlanStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Extract.class, new ExtractStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Coverage.class, new CoverageStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Claim.class, claimStub),
                ActivityFailureWrapper.wrap(ResearchActivities.Verify.class, new VerifyStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Conflict.class, new ConflictStub()),
                ActivityFailureWrapper.wrap(ResearchActivities.Report.class, new ReportStub()));

        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void workflowCompletesWithReportAndUsage() {
        ResearchWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                ResearchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ResearchWorkflow.TASK_QUEUE)
                        .setWorkflowId("research/test-1")
                        .build());

        ResearchRequest request = new ResearchRequest(
                "task-1", "Temporal vs LangGraph", Depth.QUICK, 5, 500_000, 0, "auto",
                List.of(), List.of());

        String result = workflow.run(request);

        assertEquals("task-1", result);
        ResearchProgress progress = workflow.progress();
        assertEquals("SUCCEEDED", progress.status());
        assertEquals("report-1", progress.reportId());
        assertEquals("COVERAGE_REACHED", progress.stopReason());
        assertTrue(progress.usedTokens() > 0);
        assertNotNull(progress.phase());
    }

    @Test
    void failedRunStillRecordsUsageAlreadySpent() {
        claimStub.fail = true;
        ResearchWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                ResearchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ResearchWorkflow.TASK_QUEUE)
                        .setWorkflowId("research/test-fail")
                        .build());

        assertThrows(RuntimeException.class, () -> workflow.run(new ResearchRequest(
                "task-2", "Temporal vs LangGraph", Depth.QUICK, 5, 500_000, 0, "auto",
                List.of(), List.of())));

        // Plan/extract/coverage already ran and were charged; a failure must not report 0.
        assertTrue(taskStub.failedTokens > 0, "failed task lost its token counter");
        assertTrue(taskStub.failedCostMicros > 0, "failed task lost its cost counter");
    }

    @Test
    void nonRetryableSearchFailureIsNotRetriedAndKeepsErrorCode() {
        searchStub.failNonRetryable = true;
        ResearchWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                ResearchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ResearchWorkflow.TASK_QUEUE)
                        .setWorkflowId("research/test-quota")
                        .build());

        assertThrows(RuntimeException.class, () -> workflow.run(new ResearchRequest(
                "task-3", "Temporal vs LangGraph", Depth.QUICK, 5, 500_000, 0, "auto",
                List.of(), List.of())));

        // RetryOptions allows 3 attempts; a permanent failure must be attempted once.
        assertEquals(1, searchStub.calls, "non-retryable search failure was retried");
        assertEquals("SEARCH_UNAVAILABLE", taskStub.failedCode,
                "business error code was flattened to WORKFLOW_ERROR");
    }

    // --- deterministic activity stubs (no Spring, no DB) ---

    static final class TaskStub implements ResearchActivities.Task {
        long failedTokens;
        long failedCostMicros;
        String failedCode;

        @Override
        public TaskContext init(ResearchRequest request) {
            return new TaskContext(request.taskId(), request.question(), request.depth(),
                    request.depth().maxRounds(), request.depth().topK(), request.depth().maxSources(),
                    System.currentTimeMillis() + 300_000,
                    request.budgetTokens(), request.budgetCostMicros(), request.language(),
                    request.allowedDomains(), request.excludedDomains());
        }

        @Override
        public void finalizeTask(String taskId, ResearchResult result) {
        }

        @Override
        public void markFailed(String taskId, String errorCode, String errorMessage,
                               long usedTokens, long usedCostMicros) {
            failedCode = errorCode;
            failedTokens = usedTokens;
            failedCostMicros = usedCostMicros;
        }

        @Override
        public void markCanceled(String taskId, long usedTokens, long usedCostMicros) {
        }
    }

    static final class EventStub implements ResearchActivities.Events {
        @Override
        public void emit(TaskEvent event) {
        }
    }

    static final class PlanStub implements ResearchActivities.Plan {
        @Override
        public PlanResult create(TaskContext context) {
            return new PlanResult(List.of(
                    new SubQuestion("q1", 1, "Approach", "What is the approach?", List.of("query one"), "OPEN"),
                    new SubQuestion("q2", 2, "Tradeoffs", "What are the tradeoffs?", List.of("query two"), "OPEN")),
                    new Usage(100, 50, 250), "test.v1");
        }

        @Override
        public PlanResult refine(RefineInput input) {
            return new PlanResult(input.subQuestions(), Usage.tokens(10, 5), "test.v1");
        }
    }

    static final class SearchStub implements ResearchActivities.Search {
        int calls;
        boolean failNonRetryable;

        @Override
        public SearchResult search(SearchInput input) {
            calls++;
            if (failNonRetryable) {
                throw new DraException(ErrorCode.SEARCH_UNAVAILABLE,
                        "Tavily HTTP 432: exceeds plan limit", false);
            }
            return new SearchResult(List.of(
                    new SearchHit("https://example.org/" + input.subQuestion().id() + "/1",
                            "Result 1", "snippet", 1, "mock", "2026-01-01"),
                    new SearchHit("https://example.org/" + input.subQuestion().id() + "/2",
                            "Result 2", "snippet", 2, "mock", "2026-01-01")),
                    "mock", Usage.ZERO, 0);
        }
    }

    static final class FetchStub implements ResearchActivities.Fetch {
        @Override
        public SourceRef fetch(FetchInput input) {
            return new SourceRef("source-" + Math.abs(input.url().hashCode()), input.url(),
                    "Fetched", true, null, "hash", 100);
        }
    }

    static final class ExtractStub implements ResearchActivities.Extract {
        @Override
        public ExtractResult extract(ExtractInput input) {
            return new ExtractResult(List.of("ev-" + input.sourceId()), Usage.tokens(50, 25));
        }
    }

    static final class CoverageStub implements ResearchActivities.Coverage {
        @Override
        public CoverageReport assess(CoverageInput input) {
            List<SubQuestionCoverage> coverage = input.subQuestions().stream()
                    .map(q -> new SubQuestionCoverage(q.id(), "COVERED", 0.9, List.of()))
                    .toList();
            return new CoverageReport(0.9, coverage, Usage.tokens(20, 10));
        }
    }

    static final class ClaimStub implements ResearchActivities.Claim {
        boolean fail;

        @Override
        public List<ClaimDraft> synthesize(SynthesizeInput input) {
            if (fail) {
                throw new IllegalStateException("claim synthesis blew up");
            }
            return List.of(new ClaimDraft("claim-1", "s1", "A claim", List.of("ev-1"), 3));
        }
    }

    static final class VerifyStub implements ResearchActivities.Verify {
        @Override
        public Verification verify(VerifyInput input) {
            return new Verification(input.claimId(), "PASS", "SUPPORT", "SUPPORTED",
                    0.9, "supported", List.of(), Usage.tokens(10, 5));
        }
    }

    static final class ConflictStub implements ResearchActivities.Conflict {
        @Override
        public ConflictReport detect(ConflictInput input) {
            return ConflictReport.empty();
        }
    }

    static final class ReportStub implements ResearchActivities.Report {
        @Override
        public ReportRef generate(ReportInput input) {
            return new ReportRef("report-1", 1, "# Report", Usage.tokens(30, 15));
        }
    }
}
