package com.dra.contracts.dto;

import java.util.List;

/** A draft claim must be bound to real evidence IDs before it can reach a report. */
public record ClaimDraft(
        String id,
        String sectionKey,
        String statement,
        List<String> evidenceIds,
        int importance) {

    public ClaimDraft {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        importance = Math.max(1, Math.min(5, importance));
    }
}
