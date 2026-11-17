package com.dra.domain.model;

import java.util.List;

public record VerificationRow(
        String id,
        String claimId,
        int round,
        String ruleVerdict,
        String llmVerdict,
        String finalVerdict,
        String rationale,
        List<String> checkedSourceIds,
        String verifierProfile,
        String promptVersion,
        double judgeConfidence,
        double ruleRatio,
        double diversityScore,
        double confidence) {

    public VerificationRow {
        checkedSourceIds = checkedSourceIds == null ? List.of() : List.copyOf(checkedSourceIds);
    }
}
