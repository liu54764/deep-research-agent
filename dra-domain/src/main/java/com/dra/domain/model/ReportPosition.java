package com.dra.domain.model;

import java.util.List;

/** One side of a detected disagreement, pointing at a real claim. */
public record ReportPosition(
        String claimId,
        String statement,
        String stance,
        List<String> urls) {

    public ReportPosition {
        urls = urls == null ? List.of() : List.copyOf(urls);
    }
}
