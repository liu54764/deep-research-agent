package com.dra.domain.model;

import java.util.List;

/** A disagreement between claims. The report presents both sides without adjudicating. */
public record ReportConflict(
        String topic,
        String summary,
        List<ReportPosition> positions) {

    public ReportConflict {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
