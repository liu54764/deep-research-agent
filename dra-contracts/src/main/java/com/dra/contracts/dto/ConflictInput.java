package com.dra.contracts.dto;

import java.util.List;

/** Conflict detection works on persisted claims, so the payload carries IDs only. */
public record ConflictInput(
        String taskId,
        int round,
        List<String> claimIds) {

    public ConflictInput {
        claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
    }
}
