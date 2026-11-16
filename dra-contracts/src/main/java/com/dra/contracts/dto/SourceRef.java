package com.dra.contracts.dto;

/**
 * Reference to a fetched source. The body never travels through workflow history;
 * activities load it by sourceId.
 */
public record SourceRef(
        String sourceId,
        String url,
        String title,
        boolean ok,
        String errorCode,
        String contentHash,
        int contentLength) {

    public static SourceRef failed(String url, String errorCode) {
        return new SourceRef(null, url, null, false, errorCode, null, 0);
    }
}
