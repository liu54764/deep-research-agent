package com.dra.contracts.dto;

import java.util.List;

public record PlanResult(
        List<SubQuestion> subQuestions,
        Usage usage,
        String promptVersion,
        boolean degraded,
        String warning) {

    public PlanResult {
        subQuestions = subQuestions == null ? List.of() : List.copyOf(subQuestions);
        usage = usage == null ? Usage.ZERO : usage;
        promptVersion = promptVersion == null ? "unknown" : promptVersion;
        warning = warning == null ? "" : warning;
    }

    public PlanResult(List<SubQuestion> subQuestions, Usage usage, String promptVersion) {
        this(subQuestions, usage, promptVersion, false, "");
    }
}
