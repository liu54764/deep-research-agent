package com.dra.worker.workflow;

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
import com.dra.contracts.dto.SynthesizeInput;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.dto.Usage;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import com.dra.contracts.model.EventTypes;
import com.dra.contracts.model.Phase;
import com.dra.contracts.model.StopReason;
import com.dra.contracts.model.TaskStatus;
import com.dra.contracts.workflow.ResearchWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestration topology lives in plain Java: phases, loops, budget and stop conditions.
 * No IO here — every side effect is an activity; large bodies are passed by ID only.
 */
public class ResearchWorkflowImpl implements ResearchWorkflow {

    private static final String IO_QUEUE = "drs-io";
    private static final String LLM_QUEUE = "drs-llm";
    private static final Logger log = Workflow.getLogger(ResearchWorkflowImpl.class);

    private final ResearchActivities.Task taskActivities =
            Workflow.newActivityStub(ResearchActivities.Task.class, options(IO_QUEUE, 30));
    private final ResearchActivities.Events eventActivities =
            Workflow.newActivityStub(ResearchActivities.Events.class, options(IO_QUEUE, 10));
    private final ResearchActivities.Search searchActivities =
            Workflow.newActivityStub(ResearchActivities.Search.class, options(IO_QUEUE, 30, 5));
    private final ResearchActivities.Fetch fetchActivities =
            Workflow.newActivityStub(ResearchActivities.Fetch.class, options(IO_QUEUE, 30, 5));
    private final ResearchActivities.Plan planActivities =
            Workflow.newActivityStub(ResearchActivities.Plan.class, options(LLM_QUEUE, 120));
    private final ResearchActivities.Extract extractActivities =
            Workflow.newActivityStub(ResearchActivities.Extract.class, options(LLM_QUEUE, 90));
    private final ResearchActivities.Coverage coverageActivities =
            Workflow.newActivityStub(ResearchActivities.Coverage.class, options(LLM_QUEUE, 90));
    private final ResearchActivities.Claim claimActivities =
            Workflow.newActivityStub(ResearchActivities.Claim.class, options(LLM_QUEUE, 120));
    private final ResearchActivities.Verify verifyActivities =
            Workflow.newActivityStub(ResearchActivities.Verify.class, options(LLM_QUEUE, 60));
    private final ResearchActivities.Conflict conflictActivities =
            Workflow.newActivityStub(ResearchActivities.Conflict.class, options(LLM_QUEUE, 60));
    private final ResearchActivities.Report reportActivities =
            Workflow.newActivityStub(ResearchActivities.Report.class, options(LLM_QUEUE, 240));

    private final List<String> warnings = new ArrayList<>();
    private final List<String> evidenceIds = new ArrayList<>();
    private boolean paused;
    private Phase phase = Phase.INIT;
    private TaskStatus status = TaskStatus.QUEUED;
    private int round;
    private int done;
    private int total;
    private long usedTokens;
    private long usedCostMicros;
    private String stopReason;
    private String reportId;
    private String taskId;

    @Override
    public String run(ResearchRequest request) {
        TaskContext ctx = taskActivities.init(request);
        taskId = ctx.taskId();
        status = TaskStatus.RUNNING;

        try {
            emitPhase(Phase.PLAN);
            PlanResult plan = planActivities.create(ctx);
            charge(plan.usage());
            if (plan.degraded()) {
                addWarning("PLAN_DEGRADED", plan.warning());
            }
            List<SubQuestion> subQuestions = plan.subQuestions();
            if (subQuestions.isEmpty()) {
                addWarning("PLAN_EMPTY", "planner returned no sub-questions");
            }

            for (round = 1; round <= ctx.maxRounds(); round++) {
                awaitResumeIfPaused();
                if (budgetOrDeadlineReached(ctx)) {
                    break;
                }

                Map<String, List<SearchHit>> hits = search(ctx, subQuestions);
                List<SearchHit> picked = pick(hits, subQuestions, Math.min(ctx.maxSources(), 6));
                if (picked.isEmpty()) {
                    addWarning("NO_SOURCES", "no search results for round " + round);
                    break;
                }
                List<SourceRef> sources = fetch(ctx, picked);
                extract(ctx, subQuestions, sources);

                emitPhase(Phase.COVERAGE);
                CoverageReport coverage = coverageActivities.assess(
                        new CoverageInput(ctx.taskId(), round, subQuestions));
                charge(coverage.usage());
                if (coverage.ratio() >= 0.8) {
                    stopReason = StopReason.COVERAGE_REACHED.name();
                    break;
                }
            }

            if (stopReason == null) {
                stopReason = StopReason.MAX_ROUNDS.name();
            }

            emitPhase(Phase.SYNTHESIZE);
            List<ClaimDraft> claims = claimActivities.synthesize(
                    new SynthesizeInput(ctx.taskId(), subQuestions, Math.max(1, round),
                            List.copyOf(new java.util.LinkedHashSet<>(evidenceIds))));

            emitPhase(Phase.VERIFY);
            List<Verification> verifications = verify(ctx, claims);

            emitPhase(Phase.CONFLICT);
            ConflictReport conflicts = conflictActivities.detect(new ConflictInput(
                    ctx.taskId(), Math.max(1, round),
                    claims.stream().map(ClaimDraft::id).toList()));
            charge(conflicts.usage());
            if (!conflicts.conflicts().isEmpty()) {
                // Must go through addWarning so the report (built next) and the status
                // endpoint see the same warning list.
                addWarning("CONFLICTS_FOUND",
                        conflicts.conflicts().size() + " disagreements detected");
            }

            emitPhase(Phase.REPORT);
            ReportRef report = reportActivities.generate(
                    new ReportInput(ctx.taskId(), verifications, List.copyOf(warnings), stopReason));
            charge(report.usage());
            reportId = report.reportId();

            status = TaskStatus.SUCCEEDED;
            taskActivities.finalizeTask(ctx.taskId(),
                    new ResearchResult(ctx.taskId(), reportId, stopReason, usedTokens, usedCostMicros));
            emitPhase(Phase.DONE);
            emit(TaskEvent.done(ctx.taskId(), reportId, stopReason));
            return ctx.taskId();
        } catch (CanceledFailure e) {
            cleanupCanceled(ctx.taskId());
            throw e;
        } catch (Exception e) {
            CanceledFailure canceled = canceledCause(e);
            if (canceled != null) {
                cleanupCanceled(ctx.taskId());
                throw canceled;
            }
            ApplicationFailure application = applicationCause(e);
            if (application != null && isErrorCode(application.getType())) {
                // Non-retryable business failures carry their code as the ApplicationFailure type
                // (see ActivityFailureWrapper); surface it instead of a generic WORKFLOW_ERROR.
                cleanupFailed(ctx.taskId(), application.getType(), application.getOriginalMessage());
            } else {
                cleanupFailed(ctx.taskId(), "WORKFLOW_ERROR", e.getMessage());
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(e);
        }
    }

    /** Activity failures wrap the real CanceledFailure; walk the cause chain. */
    private static CanceledFailure canceledCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CanceledFailure canceled) {
                return canceled;
            }
            current = current.getCause();
        }
        return null;
    }

    /** Activity failures wrap the real ApplicationFailure; walk the cause chain. */
    private static ApplicationFailure applicationCause(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApplicationFailure application) {
                return application;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Generic exceptions reach the workflow as ApplicationFailure whose type is the Java class
     * name; only a type that names one of our stable error codes is a business failure.
     */
    private static boolean isErrorCode(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        try {
            ErrorCode.valueOf(type);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    @SignalMethod
    public void pause() {
        if (!status.terminal()) {
            paused = true;
            status = TaskStatus.PAUSED;
        }
    }

    @Override
    @SignalMethod
    public void resume() {
        paused = false;
        if (!status.terminal()) {
            status = TaskStatus.RUNNING;
        }
    }

    @Override
    @QueryMethod
    public ResearchProgress progress() {
        return new ResearchProgress(taskId, status.name(), phase.name(), round, done, total,
                usedTokens, usedCostMicros, List.copyOf(warnings), stopReason, reportId);
    }

    private Map<String, List<SearchHit>> search(TaskContext ctx, List<SubQuestion> subQuestions) {
        emitPhase(Phase.SEARCH);
        awaitResumeIfPaused();
        // Hits stay grouped per sub-question so fetch slots can be shared fairly.
        Map<String, List<SearchHit>> hits = new LinkedHashMap<>();
        done = 0;
        total = subQuestions.size();
        // MVP: sequential fan-out; M6 replaces this with batched Async.function calls.
        for (SubQuestion subQuestion : subQuestions) {
            SearchResult result = searchActivities.search(
                    new SearchInput(ctx.taskId(), round, subQuestion, ctx.topK()));
            charge(result.usage());
            hits.put(subQuestion.id(), new ArrayList<>(result.hits()));
            progress(Phase.SEARCH, ++done, total);
        }
        return hits;
    }

    private List<SourceRef> fetch(TaskContext ctx, List<SearchHit> picked) {
        emitPhase(Phase.FETCH);
        awaitResumeIfPaused();
        List<SourceRef> sources = new ArrayList<>();
        done = 0;
        total = picked.size();
        for (SearchHit hit : picked) {
            try {
                SourceRef ref = fetchActivities.fetch(new FetchInput(ctx.taskId(), hit.url()));
                if (ref.ok()) {
                    sources.add(ref);
                } else {
                    addWarning("FETCH_FAILED", hit.url());
                }
            } catch (Exception e) {
                addWarning("FETCH_FAILED", hit.url());
            }
            progress(Phase.FETCH, ++done, total);
        }
        return sources;
    }

    private void extract(TaskContext ctx, List<SubQuestion> subQuestions, List<SourceRef> sources) {
        emitPhase(Phase.EXTRACT);
        awaitResumeIfPaused();
        done = 0;
        total = sources.size();
        for (SourceRef source : sources) {
            ExtractResult result = extractActivities.extract(
                    new ExtractInput(ctx.taskId(), source.sourceId(), subQuestions, round));
            charge(result.usage());
            evidenceIds.addAll(result.evidenceIds());
            if (result.evidenceIds().isEmpty()) {
                addWarning("EXTRACT_EMPTY", source.url());
            }
            progress(Phase.EXTRACT, ++done, total);
        }
    }

    private List<Verification> verify(TaskContext ctx, List<ClaimDraft> claims) {
        List<Verification> verifications = new ArrayList<>(claims.size());
        done = 0;
        total = claims.size();
        for (ClaimDraft claim : claims) {
            Verification verification = verifyActivities.verify(
                    new VerifyInput(ctx.taskId(), claim.id(), Math.max(1, round)));
            charge(verification.usage());
            verifications.add(verification);
            progress(Phase.VERIFY, ++done, total);
        }
        return verifications;
    }

    private boolean budgetOrDeadlineReached(TaskContext ctx) {
        long budgetLimit = (long) (ctx.budgetTokens() * 0.85);
        if (usedTokens >= budgetLimit) {
            stopReason = StopReason.BUDGET_EXHAUSTED.name();
            addWarning("BUDGET_EXHAUSTED", "token budget exhausted");
            return true;
        }
        long now = Workflow.currentTimeMillis();
        if (now >= ctx.deadlineEpochMs()) {
            stopReason = StopReason.DEADLINE_REACHED.name();
            addWarning("DEADLINE_REACHED", "deadline reached");
            return true;
        }
        return false;
    }

    private void awaitResumeIfPaused() {
        if (!paused) {
            return;
        }
        status = TaskStatus.PAUSED;
        emit(TaskEvent.of(taskId, EventTypes.PAUSED,
                Map.of("phase", phase.name(), "round", round)));
        while (paused) {
            Workflow.await(Duration.ofSeconds(30), () -> !paused);
        }
        status = TaskStatus.RUNNING;
        emit(TaskEvent.of(taskId, EventTypes.RESUMED,
                Map.of("phase", phase.name(), "round", round)));
    }

    private void charge(Usage usage) {
        if (usage == null) {
            return;
        }
        usedTokens += usage.totalTokens();
        usedCostMicros += usage.costMicros();
    }

    private void emitPhase(Phase next) {
        phase = next;
        emit(TaskEvent.phase(taskId, next, round));
    }

    private void progress(Phase current, int currentDone, int currentTotal) {
        done = currentDone;
        total = currentTotal;
        emit(TaskEvent.progress(taskId, current, currentDone, currentTotal));
    }

    private void addWarning(String code, String message) {
        if (warnings.size() >= 20) {
            return;
        }
        warnings.add(code + ": " + message);
        emit(TaskEvent.warning(taskId, code, message));
    }

    private void emit(TaskEvent event) {
        try {
            eventActivities.emit(event);
        } catch (Exception e) {
            // Progress delivery must never fail the research task.
            log.warn("progress event dropped: {}", event.type());
        }
    }

    private void cleanupFailed(String taskId, String code, String message) {
        String safeMessage = message == null ? "unknown error"
                : message.substring(0, Math.min(message.length(), 900));
        status = TaskStatus.FAILED;
        try {
            Workflow.newDetachedCancellationScope(() -> {
                try {
                    taskActivities.markFailed(taskId, code, safeMessage,
                            usedTokens, usedCostMicros);
                } catch (Exception ignored) {
                    // best effort
                }
                try {
                    eventActivities.emit(TaskEvent.error(taskId, code, safeMessage));
                } catch (Exception ignored) {
                    // best effort
                }
            }).run();
        } catch (Exception ignored) {
            // workflow is already failing
        }
    }

    private void cleanupCanceled(String taskId) {
        status = TaskStatus.CANCELED;
        try {
            Workflow.newDetachedCancellationScope(() -> {
                try {
                    taskActivities.markCanceled(taskId, usedTokens, usedCostMicros);
                } catch (Exception ignored) {
                    // best effort
                }
                try {
                    eventActivities.emit(TaskEvent.error(taskId, "CANCELED", "task canceled"));
                } catch (Exception ignored) {
                    // best effort
                }
            }).run();
        } catch (Exception ignored) {
            // workflow is already cancelled
        }
    }

    /**
     * Shares fetch slots across sub-questions round-robin: every sub-question gets its best
     * hit before any gets a second one. A plain "first N of all hits" cut lets early
     * sub-questions consume the whole budget, so a question's later half ends up with no
     * sources at all (observed: an SSE sub-question with 0 evidence, LangGraph side with 4).
     */
    static List<SearchHit> pick(Map<String, List<SearchHit>> hitsBySubQuestion,
                                List<SubQuestion> subQuestions, int limit) {
        int cap = Math.max(1, limit);
        Map<String, SearchHit> unique = new LinkedHashMap<>();
        List<List<SearchHit>> queues = new ArrayList<>();
        for (SubQuestion subQuestion : subQuestions) {
            List<SearchHit> hits = hitsBySubQuestion.get(subQuestion.id());
            if (hits != null && !hits.isEmpty()) {
                queues.add(hits);
            }
        }
        int maxDepth = queues.stream().mapToInt(List::size).max().orElse(0);
        for (int depth = 0; depth < maxDepth && unique.size() < cap; depth++) {
            for (List<SearchHit> queue : queues) {
                if (depth >= queue.size()) {
                    continue;
                }
                SearchHit hit = queue.get(depth);
                if (hit == null || hit.url() == null) {
                    continue;
                }
                unique.putIfAbsent(hit.url(), hit);
                if (unique.size() >= cap) {
                    break;
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static ActivityOptions options(String queue, int startToCloseSeconds) {
        return options(queue, startToCloseSeconds, 0);
    }

    private static ActivityOptions options(String queue, int startToCloseSeconds,
                                           int heartbeatSeconds) {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setTaskQueue(queue)
                .setStartToCloseTimeout(Duration.ofSeconds(startToCloseSeconds))
                .setRetryOptions(io.temporal.common.RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(300))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(3)
                        .build());
        if (heartbeatSeconds > 0) {
            // Search/fetch heartbeat from HeartbeatGuard; without this a dead worker is only
            // noticed when startToClose expires (measured as ~28s of extra wall time in S4).
            builder.setHeartbeatTimeout(Duration.ofSeconds(heartbeatSeconds));
        }
        return builder.build();
    }
}
