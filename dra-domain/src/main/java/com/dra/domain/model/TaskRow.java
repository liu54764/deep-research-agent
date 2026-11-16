package com.dra.domain.model;

import com.dra.contracts.model.Depth;
import com.dra.contracts.model.TaskStatus;

/** Persisted task row; the API and activities exchange this instead of JPA entities. */
public record TaskRow(
        String id,
        String idempotencyKey,
        String requestHash,
        String question,
        TaskStatus status,
        Depth depth,
        long budgetTokens,
        long budgetCostMicros,
        long usedTokens,
        long usedCostMicros,
        String workflowId,
        String runId,
        String stopReason,
        String errorCode,
        String errorMessage,
        String reportId,
        long createdAtMs,
        Long startedAtMs,
        Long finishedAtMs) {

    public static TaskRow queued(String id,
                                 String idempotencyKey,
                                 String requestHash,
                                 String question,
                                 Depth depth,
                                 long budgetTokens,
                                 long budgetCostMicros,
                                 long createdAtMs) {
        return new TaskRow(id, idempotencyKey, requestHash, question, TaskStatus.QUEUED, depth,
                budgetTokens, budgetCostMicros, 0, 0, null, null,
                null, null, null, null, createdAtMs, null, null);
    }
}
