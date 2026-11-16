package com.dra.domain.model;

public record EvidenceView(
        String id,
        String taskId,
        String sourceId,
        String subQuestionId,
        int round,
        String quote,
        String origin,
        String sourceUrl,
        String sourceTitle) {
}
