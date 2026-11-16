package com.dra.api.web.dto;

import java.util.List;

public record TaskStatusResponse(
        String taskId,
        String status,
        String phase,
        int round,
        Progress progress,
        UsageView usage,
        List<String> warnings,
        String stopReason,
        String reportId,
        String errorCode,
        String errorMessage,
        String createdAt,
        String startedAt,
        String finishedAt) {

    public record Progress(int done, int total, int percent) {
    }

    public record UsageView(long tokens, long costMicros) {
    }
}
