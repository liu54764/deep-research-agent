package com.dra.contracts.dto;

import java.util.List;

public record Verification(
        String claimId,
        String ruleVerdict,
        String llmVerdict,
        String finalVerdict,
        double confidence,
        String rationale,
        List<String> checkedSourceIds,
        Usage usage) {

    public Verification {
        checkedSourceIds = checkedSourceIds == null ? List.of() : List.copyOf(checkedSourceIds);
        usage = usage == null ? Usage.ZERO : usage;
        confidence = Math.max(0, Math.min(1, confidence));
    }
}
