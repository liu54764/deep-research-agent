package com.dra.domain.model;

public record ClaimRow(
        String id,
        String taskId,
        String sectionKey,
        String statement,
        int importance,
        String status,
        double confidence) {
}
