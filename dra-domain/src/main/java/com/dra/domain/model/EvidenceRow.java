package com.dra.domain.model;

/**
 * One verbatim evidence row. {@code aspect} is the model's short label for what the quote
 * supports; {@code anchorJson} carries the character range of the quote inside the source
 * body ({"start":n,"end":m}) so the citation can point back at the exact passage.
 */
public record EvidenceRow(
        String id,
        String taskId,
        String sourceId,
        String subQuestionId,
        int round,
        String quote,
        String quoteHash,
        String origin,
        String extractVersion,
        String aspect,
        String anchorJson) {
}
