package com.dra.eval.config;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum EvalMode {
    /** Mock LLM + mock search + stub fetch; no external network, no judge. */
    SELF_TEST("self-test"),
    /** Real LLM + Tavily + fetch; judge still gated by dra.eval.judge.enabled. */
    REAL("real"),
    /** Re-score an existing run without re-running tasks (M9.3). */
    RESCORE("rescore"),
    /** Compute Cohen's kappa from a filled label file; costs nothing. */
    CALIBRATE("calibrate"),
    /** Print the planned workload and cost estimate, call nothing. */
    ESTIMATE("estimate");

    private final String value;

    EvalMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EvalMode from(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase().replace('_', '-');
        for (EvalMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown mode '" + raw + "', expected one of "
                + Arrays.stream(values()).map(EvalMode::value).collect(Collectors.joining(", ")));
    }
}
