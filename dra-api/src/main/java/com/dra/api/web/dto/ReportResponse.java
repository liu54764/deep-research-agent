package com.dra.api.web.dto;

import com.dra.contracts.dto.ReportDocument;

/**
 * {@code markdown} is the rendered deliverable; {@code document} is the structured view
 * (sections, numbered references, conflicts, stats) used by clients and evals.
 */
public record ReportResponse(
        String taskId,
        int version,
        String markdown,
        ReportDocument document) {
}
