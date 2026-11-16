package com.dra.contracts.dto;

import java.util.List;

public record RefineInput(
        String taskId,
        int round,
        List<SubQuestion> subQuestions,
        CoverageReport coverage) {

    public RefineInput {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }
}
