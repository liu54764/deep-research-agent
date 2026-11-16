package com.dra.contracts.dto;

public record ResearchResult(
        String taskId,
        String reportId,
        String stopReason,
        long usedTokens,
        long usedCostMicros) {

    public ResearchResult(String taskId, String reportId, String stopReason) {
        this(taskId, reportId, stopReason, 0, 0);
    }
}
