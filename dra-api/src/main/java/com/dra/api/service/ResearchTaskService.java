package com.dra.api.service;

import com.dra.api.web.dto.ReportResponse;
import com.dra.api.web.dto.SubmitTaskRequest;
import com.dra.api.web.dto.SubmitTaskResponse;
import com.dra.api.web.dto.TaskStatusResponse;
import com.dra.api.web.dto.TaskSummaryResponse;
import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import com.dra.common.hash.Hashing;
import com.dra.common.id.Ids;
import com.dra.contracts.dto.ReportDocument;
import com.dra.contracts.dto.ResearchRequest;
import com.dra.contracts.model.Depth;
import com.dra.domain.model.ReportRow;
import com.dra.domain.model.TaskRow;
import com.dra.domain.port.ProgressStream;
import com.dra.domain.port.ReportRepository;
import com.dra.domain.port.TaskRepository;
import io.temporal.api.common.v1.WorkflowExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskService.class);

    private final TaskRepository tasks;
    private final ReportRepository reports;
    private final ProgressStream progressStream;
    private final WorkflowGateway workflowGateway;
    private final ObjectMapper objectMapper;

    public ResearchTaskService(TaskRepository tasks,
                               ReportRepository reports,
                               ProgressStream progressStream,
                               WorkflowGateway workflowGateway,
                               ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.reports = reports;
        this.progressStream = progressStream;
        this.workflowGateway = workflowGateway;
        this.objectMapper = objectMapper;
    }

    public SubmitTaskResponse submit(String idempotencyKey, SubmitTaskRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new DraException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key header is required (max 80 chars)", false);
        }

        Depth depth = parseDepth(request.depth());
        String language = blankToDefault(request.language(), "auto");
        int deadlineMinutes = request.deadlineMinutes() == null ? depth.defaultDeadlineMinutes()
                : request.deadlineMinutes();
        long budgetTokens = request.budgetTokens() == null ? depth.defaultBudgetTokens()
                : request.budgetTokens();
        long budgetCostMicros = request.budgetCostMicros() == null ? 0 : request.budgetCostMicros();
        List<String> allowedDomains = safeList(request.allowedDomains());
        List<String> excludedDomains = safeList(request.excludedDomains());

        String requestHash = Hashing.sha256Hex(String.join("|",
                request.question(), depth.name(), String.valueOf(deadlineMinutes),
                String.valueOf(budgetTokens), String.valueOf(budgetCostMicros), language,
                String.join(",", allowedDomains), String.join(",", excludedDomains)));

        String taskId = Ids.ulid();
        TaskRow row = TaskRow.queued(taskId, idempotencyKey, requestHash, request.question(), depth,
                budgetTokens, budgetCostMicros, System.currentTimeMillis());
        boolean inserted = tasks.insert(row);
        if (!inserted) {
            TaskRow existing = tasks.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new DraException(ErrorCode.INTERNAL_ERROR,
                            "idempotency key race", true));
            if (!existing.requestHash().equals(requestHash)) {
                throw new DraException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was reused with a different request body", false);
            }
            return toResponse(existing);
        }

        ResearchRequest workflowRequest = new ResearchRequest(taskId, request.question(), depth,
                deadlineMinutes, budgetTokens, budgetCostMicros, language, allowedDomains, excludedDomains);
        try {
            WorkflowExecution execution = workflowGateway.start(taskId, workflowRequest);
            tasks.attachWorkflow(taskId, WorkflowGateway.workflowId(taskId), execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("workflow already started for task {}, treating as idempotent retry", taskId);
        } catch (Exception e) {
            // Workflow never started, so nothing was spent.
            tasks.markFailed(taskId, ErrorCode.TEMPORAL_UNAVAILABLE.name(), e.getMessage(), 0, 0);
            throw new DraException(ErrorCode.TEMPORAL_UNAVAILABLE,
                    "cannot start research workflow: " + e.getMessage(), true, e);
        }

        return toResponse(tasks.findById(taskId).orElse(row));
    }

    public TaskRow requireTask(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new DraException(ErrorCode.TASK_NOT_FOUND, "task not found: " + taskId, false));
    }

    public TaskStatusResponse get(String taskId) {
        TaskRow row = requireTask(taskId);
        Map<String, String> state = progressStream.snapshot(taskId);

        String status = row.status().terminal() ? row.status().name()
                : state.getOrDefault("status", row.status().name());
        String phase = state.getOrDefault("phase", row.status().terminal() ? "DONE" : "INIT");
        int round = parseInt(state.get("round"), 0);
        int done = parseInt(state.get("done"), 0);
        int total = parseInt(state.get("total"), 0);
        int percent = parseInt(state.get("percent"), total <= 0 ? 0 : Math.min(100, done * 100 / total));
        String stopReason = firstNonBlank(row.stopReason(), state.get("stopReason"));
        String reportId = firstNonBlank(row.reportId(), state.get("reportId"));
        String errorCode = firstNonBlank(row.errorCode(), state.get("errorCode"));
        String errorMessage = firstNonBlank(row.errorMessage(), state.get("errorMessage"));
        long tokens = Math.max(row.usedTokens(), parseLong(state.get("usedTokens"), 0));
        long costMicros = Math.max(row.usedCostMicros(), parseLong(state.get("usedCostMicros"), 0));

        List<String> warnings = new ArrayList<>();
        String joinedWarnings = state.get("warnings");
        if (joinedWarnings != null && !joinedWarnings.isBlank()) {
            for (String value : joinedWarnings.split("\n")) {
                if (!value.isBlank() && !warnings.contains(value)) {
                    warnings.add(value);
                }
            }
        } else if (state.get("lastWarning") != null && !state.get("lastWarning").isBlank()) {
            // Older tasks only stored the last warning; keep them readable.
            warnings.add(state.get("lastWarning"));
        }

        return new TaskStatusResponse(
                row.id(), status, phase, round,
                new TaskStatusResponse.Progress(done, total, percent),
                new TaskStatusResponse.UsageView(tokens, costMicros),
                List.copyOf(warnings), stopReason, reportId, errorCode, errorMessage,
                iso(row.createdAtMs()), iso(row.startedAtMs()), iso(row.finishedAtMs()));
    }

    public TaskStatusResponse pause(String taskId) {
        TaskRow row = requireTask(taskId);
        if (row.status().terminal()) {
            throw new DraException(ErrorCode.TASK_TERMINAL, "task is already " + row.status(), false);
        }
        workflowGateway.pause(taskId);
        return get(taskId);
    }

    public TaskStatusResponse resume(String taskId) {
        TaskRow row = requireTask(taskId);
        if (row.status().terminal()) {
            throw new DraException(ErrorCode.TASK_TERMINAL, "task is already " + row.status(), false);
        }
        workflowGateway.resume(taskId);
        return get(taskId);
    }

    public TaskStatusResponse cancel(String taskId) {
        TaskRow row = requireTask(taskId);
        if (row.status().terminal()) {
            return get(taskId);
        }
        workflowGateway.cancel(taskId);
        return get(taskId);
    }

    public ReportResponse report(String taskId) {
        requireTask(taskId);
        ReportRow report = reports.findLatest(taskId)
                .orElseThrow(() -> new DraException(ErrorCode.REPORT_NOT_READY,
                        "report is not ready for task " + taskId, false));
        return new ReportResponse(taskId, report.version(), report.markdown(),
                parseDocument(report.metaJson()));
    }

    private ReportDocument parseDocument(String metaJson) {
        if (metaJson == null || metaJson.isBlank() || "{}".equals(metaJson.trim())) {
            return null;
        }
        try {
            return objectMapper.readValue(metaJson, ReportDocument.class);
        } catch (Exception e) {
            log.warn("cannot parse report document for task: {}", e.getMessage());
            return null;
        }
    }

    public List<TaskSummaryResponse> recent(int limit) {
        return tasks.findRecent(limit).stream()
                .map(row -> new TaskSummaryResponse(row.id(), row.status().name(), row.question(),
                        row.stopReason(), iso(row.createdAtMs())))
                .toList();
    }

    private static SubmitTaskResponse toResponse(TaskRow row) {
        String base = "/api/v1/research/tasks/" + row.id();
        return new SubmitTaskResponse(row.id(), row.status().name(), base, base + "/events",
                iso(row.createdAtMs()));
    }

    private static Depth parseDepth(String raw) {
        if (raw == null || raw.isBlank()) {
            return Depth.STANDARD;
        }
        try {
            return Depth.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DraException(ErrorCode.VALIDATION_ERROR, "unknown depth: " + raw, false);
        }
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return value == null ? defaultValue : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String iso(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }
}
