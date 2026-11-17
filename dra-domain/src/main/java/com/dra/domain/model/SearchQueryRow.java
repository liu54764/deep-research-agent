package com.dra.domain.model;

/** One executed search query, persisted so recall problems can be traced after the fact. */
public record SearchQueryRow(
        String id,
        String taskId,
        String subQuestionId,
        int round,
        String provider,
        String queryText,
        String queryHash,
        String status,
        int resultCount,
        long latencyMs,
        long costMicros) {
}
