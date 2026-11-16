package com.dra.contracts.dto;

import java.util.List;

public record CoverageInput(String taskId, int round, List<SubQuestion> subQuestions) {

    public CoverageInput {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }
}
