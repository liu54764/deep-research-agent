package com.dra.contracts.dto;

import com.dra.contracts.model.Depth;

import java.util.List;

/** Immutable execution context produced by task.init and carried through the workflow. */
public record TaskContext(
        String taskId,
        String question,
        Depth depth,
        int maxRounds,
        int topK,
        int maxSources,
        long deadlineEpochMs,
        long budgetTokens,
        long budgetCostMicros,
        String language,
        List<String> allowedDomains,
        List<String> excludedDomains) {

    public TaskContext {
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        excludedDomains = excludedDomains == null ? List.of() : List.copyOf(excludedDomains);
    }
}
