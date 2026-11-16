package com.dra.contracts.dto;

import java.util.List;

public record CoverageReport(double ratio, List<SubQuestionCoverage> subQuestions, Usage usage) {

    public CoverageReport {
        ratio = Math.max(0, Math.min(1, ratio));
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        usage = usage == null ? Usage.ZERO : usage;
    }
}
