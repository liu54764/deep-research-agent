package com.dra.domain.model;

import java.util.List;

/**
 * Everything the report needs, loaded from the database in one shot by task ID.
 * The activity never trusts workflow memory for facts: claims, citations and stats
 * all come from persisted rows.
 */
public record ReportBundle(
        String taskId,
        String question,
        String depth,
        String stopReason,
        List<String> warnings,
        List<ReportSection> sections,
        List<ReportClaim> claims,
        List<ReportConflict> conflicts,
        ReportStats stats) {

    public ReportBundle {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        sections = sections == null ? List.of() : List.copyOf(sections);
        claims = claims == null ? List.of() : List.copyOf(claims);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public List<ReportClaim> renderableClaims() {
        return claims.stream().filter(ReportClaim::renderable).toList();
    }

    public List<ReportClaim> rejectedClaims() {
        return claims.stream().filter(claim -> !claim.renderable()).toList();
    }
}
