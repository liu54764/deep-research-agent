package com.dra.domain.model;

/** A verbatim quote tied back to a source URL, with the character range inside the body. */
public record ReportCitation(
        String evidenceId,
        String sourceId,
        String url,
        String title,
        String domain,
        String quote,
        int anchorStart,
        int anchorEnd) {
}
