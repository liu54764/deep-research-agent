package com.dra.domain.model;

/** One research sub-question, used as a report section header. */
public record ReportSection(
        String id,
        int seq,
        String title,
        String question,
        String status,
        double coverage) {
}
