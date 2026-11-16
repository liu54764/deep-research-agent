package com.dra.contracts.dto;

import java.util.List;

public record ExtractInput(
        String taskId,
        String sourceId,
        List<SubQuestion> subQuestions,
        int round) {

    public ExtractInput {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
    }
}
