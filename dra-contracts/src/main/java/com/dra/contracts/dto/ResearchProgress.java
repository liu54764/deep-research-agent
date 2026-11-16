package com.dra.contracts.dto;

import java.util.List;

/** Read-only workflow query result used by the API status endpoint. */
public record ResearchProgress(
        String taskId,
        String status,
        String phase,
        int round,
        int done,
        int total,
        long usedTokens,
        long usedCostMicros,
        List<String> warnings,
        String stopReason,
        String reportId) {

    public ResearchProgress {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
