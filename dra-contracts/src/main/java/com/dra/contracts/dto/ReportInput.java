package com.dra.contracts.dto;

import java.util.List;

public record ReportInput(
        String taskId,
        List<Verification> verifications,
        List<String> warnings,
        String stopReason) {

    public ReportInput {
        verifications = verifications == null ? List.of() : List.copyOf(verifications);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        stopReason = stopReason == null ? "MAX_ROUNDS" : stopReason;
    }
}
