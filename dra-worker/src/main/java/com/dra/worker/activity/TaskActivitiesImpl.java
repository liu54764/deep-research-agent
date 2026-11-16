package com.dra.worker.activity;

import com.dra.common.hash.Hashing;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ResearchRequest;
import com.dra.contracts.dto.ResearchResult;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.TaskRow;
import com.dra.domain.port.ProgressStream;
import com.dra.domain.port.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TaskActivitiesImpl implements ResearchActivities.Task {

    private final TaskRepository tasks;
    private final ProgressStream progressStream;

    public TaskActivitiesImpl(TaskRepository tasks, ProgressStream progressStream) {
        this.tasks = tasks;
        this.progressStream = progressStream;
    }

    @Override
    public TaskContext init(ResearchRequest request) {
        TaskRow row = tasks.findById(request.taskId()).orElse(null);
        if (row == null) {
            long now = System.currentTimeMillis();
            tasks.insert(TaskRow.queued(
                    request.taskId(),
                    "auto-" + request.taskId(),
                    Hashing.sha256Hex(request.question()),
                    request.question(),
                    request.depth(),
                    request.budgetTokens(),
                    request.budgetCostMicros(),
                    now));
        }
        tasks.markRunning(request.taskId(), null, null);

        long deadline = System.currentTimeMillis() + request.deadlineMinutes() * 60_000L;
        return new TaskContext(
                request.taskId(),
                request.question(),
                request.depth(),
                request.depth().maxRounds(),
                request.depth().topK(),
                request.depth().maxSources(),
                deadline,
                request.budgetTokens(),
                request.budgetCostMicros(),
                request.language(),
                request.allowedDomains(),
                request.excludedDomains());
    }

    @Override
    public void finalizeTask(String taskId, ResearchResult result) {
        tasks.markTerminal(taskId, TaskStatus.SUCCEEDED, result.stopReason(), result.reportId(),
                result.usedTokens(), result.usedCostMicros());
        updateState(taskId, Map.of(
                "status", TaskStatus.SUCCEEDED.name(),
                "phase", "DONE",
                "reportId", result.reportId(),
                "stopReason", result.stopReason(),
                "usedTokens", String.valueOf(result.usedTokens()),
                "usedCostMicros", String.valueOf(result.usedCostMicros()),
                "updatedAt", Instant.now().toString()));
    }

    @Override
    public void markFailed(String taskId, String errorCode, String errorMessage,
                           long usedTokens, long usedCostMicros) {
        tasks.markFailed(taskId, errorCode, errorMessage, usedTokens, usedCostMicros);
        Map<String, String> state = new LinkedHashMap<>();
        state.put("status", TaskStatus.FAILED.name());
        state.put("phase", "FAILED");
        state.put("errorCode", errorCode);
        state.put("errorMessage", errorMessage == null ? "" : errorMessage);
        state.put("usedTokens", String.valueOf(usedTokens));
        state.put("usedCostMicros", String.valueOf(usedCostMicros));
        state.put("updatedAt", Instant.now().toString());
        progressStream.updateState(taskId, state);
    }

    @Override
    public void markCanceled(String taskId, long usedTokens, long usedCostMicros) {
        tasks.markTerminal(taskId, TaskStatus.CANCELED, "CANCELED", null, usedTokens, usedCostMicros);
        updateState(taskId, Map.of(
                "status", TaskStatus.CANCELED.name(),
                "phase", "CANCELED",
                "stopReason", "CANCELED",
                "usedTokens", String.valueOf(usedTokens),
                "usedCostMicros", String.valueOf(usedCostMicros),
                "updatedAt", Instant.now().toString()));
    }

    private void updateState(String taskId, Map<String, String> fields) {
        progressStream.updateState(taskId, fields);
    }
}
