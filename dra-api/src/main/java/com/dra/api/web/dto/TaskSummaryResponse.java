package com.dra.api.web.dto;

public record TaskSummaryResponse(
        String taskId,
        String status,
        String question,
        String stopReason,
        String createdAt) {
}
