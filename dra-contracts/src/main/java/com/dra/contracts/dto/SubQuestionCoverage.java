package com.dra.contracts.dto;

import java.util.List;

public record SubQuestionCoverage(
        String subQuestionId,
        String status,
        double score,
        List<String> missingAspects) {

    public SubQuestionCoverage {
        missingAspects = missingAspects == null ? List.of() : List.copyOf(missingAspects);
    }
}
