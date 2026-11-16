package com.dra.domain.model;

/** A fetched source plus its extracted text. Raw HTML is intentionally not stored. */
public record FetchedSource(
        String sourceId,
        String taskId,
        String url,
        String title,
        boolean ok,
        String errorCode,
        String contentHash,
        int contentLength,
        String content) {
}
