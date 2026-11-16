package com.dra.contracts.dto;

import java.util.List;

public record SynthesizeInput(
        String taskId,
        List<SubQuestion> subQuestions,
        int round,
        List<String> evidenceIds) {

    public SynthesizeInput {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public SynthesizeInput(String taskId, List<SubQuestion> subQuestions, int round) {
        this(taskId, subQuestions, round, List.of());
    }
}
