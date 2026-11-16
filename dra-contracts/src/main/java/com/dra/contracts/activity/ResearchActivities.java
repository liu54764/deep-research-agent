package com.dra.contracts.activity;

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
import com.dra.contracts.dto.ResearchRequest;
import com.dra.contracts.dto.ResearchResult;
import com.dra.contracts.dto.SearchInput;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.SourceRef;
import com.dra.contracts.dto.SynthesizeInput;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * Activity contracts grouped by capability. Implementations register on different task queues:
 * io (search/fetch/events/task persistence) and llm (plan/extract/coverage/synthesize/verify/report).
 */
public final class ResearchActivities {

    private ResearchActivities() {
    }

    @ActivityInterface
    public interface Task {
        @ActivityMethod(name = "task.init.v1")
        TaskContext init(ResearchRequest request);

        @ActivityMethod(name = "task.finalize.v1")
        void finalizeTask(String taskId, ResearchResult result);

        @ActivityMethod(name = "task.markFailed.v1")
        void markFailed(String taskId, String errorCode, String errorMessage,
                        long usedTokens, long usedCostMicros);

        @ActivityMethod(name = "task.markCanceled.v1")
        void markCanceled(String taskId, long usedTokens, long usedCostMicros);
    }

    @ActivityInterface
    public interface Events {
        @ActivityMethod(name = "event.emit.v1")
        void emit(TaskEvent event);
    }

    @ActivityInterface
    public interface Plan {
        @ActivityMethod(name = "plan.create.v1")
        PlanResult create(TaskContext context);

        @ActivityMethod(name = "plan.refine.v1")
        PlanResult refine(RefineInput input);
    }

    @ActivityInterface
    public interface Search {
        @ActivityMethod(name = "search.query.v1")
        SearchResult search(SearchInput input);
    }

    @ActivityInterface
    public interface Fetch {
        @ActivityMethod(name = "fetch.source.v1")
        SourceRef fetch(FetchInput input);
    }

    @ActivityInterface
    public interface Extract {
        @ActivityMethod(name = "extract.evidence.v1")
        ExtractResult extract(ExtractInput input);
    }

    @ActivityInterface
    public interface Coverage {
        @ActivityMethod(name = "coverage.assess.v1")
        CoverageReport assess(CoverageInput input);
    }

    @ActivityInterface
    public interface Claim {
        @ActivityMethod(name = "claim.synthesize.v1")
        List<ClaimDraft> synthesize(SynthesizeInput input);
    }

    @ActivityInterface
    public interface Verify {
        @ActivityMethod(name = "claim.verify.v1")
        Verification verify(VerifyInput input);
    }

    @ActivityInterface
    public interface Conflict {
        @ActivityMethod(name = "conflict.detect.v1")
        ConflictReport detect(ConflictInput input);
    }

    @ActivityInterface
    public interface Report {
        @ActivityMethod(name = "report.generate.v1")
        ReportRef generate(ReportInput input);
    }
}
