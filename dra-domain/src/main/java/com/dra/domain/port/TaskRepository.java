package com.dra.domain.port;

import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.TaskRow;

import java.util.List;
import java.util.Optional;

public interface TaskRepository {

    /** @return false when the idempotency key already exists. */
    boolean insert(TaskRow row);

    Optional<TaskRow> findById(String id);

    Optional<TaskRow> findByIdempotencyKey(String idempotencyKey);

    void markRunning(String id, String workflowId, String runId);

    void attachWorkflow(String id, String workflowId, String runId);

    void markTerminal(String id, TaskStatus status, String stopReason, String reportId,
                      long usedTokens, long usedCostMicros);

    /**
     * Failed runs still burn tokens, so the counters are persisted here too — otherwise
     * cost dashboards and budget guardrails under-count every failure.
     */
    void markFailed(String id, String errorCode, String errorMessage,
                    long usedTokens, long usedCostMicros);

    List<TaskRow> findRecent(int limit);
}
