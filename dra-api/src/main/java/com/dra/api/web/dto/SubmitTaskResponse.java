package com.dra.api.web.dto;

public record SubmitTaskResponse(
        String taskId,
        String status,
        String statusUrl,
        String streamUrl,
        String createdAt) {
}
