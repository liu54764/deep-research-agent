package com.dra.domain.model;

/** A rendered report version. {@code metaJson} holds the structured document for the JSON API. */
public record ReportRow(
        String id,
        String taskId,
        int version,
        String markdown,
        String metaJson,
        long createdAtMs) {
}
