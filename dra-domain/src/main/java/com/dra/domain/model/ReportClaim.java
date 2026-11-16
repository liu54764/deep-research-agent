package com.dra.domain.model;

import java.util.List;

/** A verified claim plus the evidence rows it is allowed to cite. */
public record ReportClaim(
        String id,
        String sectionKey,
        String statement,
        int importance,
        String status,
        double confidence,
        List<ReportCitation> citations) {

    public ReportClaim {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Only supported/partial claims may appear as facts in the report body. */
    public boolean renderable() {
        return "SUPPORTED".equals(status) || "PARTIAL".equals(status);
    }
}
