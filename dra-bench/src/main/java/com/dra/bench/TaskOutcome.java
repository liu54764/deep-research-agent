package com.dra.bench;

import java.util.List;

/** One task as observed from the HTTP surface. Timings are wall-clock at the client. */
public record TaskOutcome(
        int index,
        String taskId,
        String question,
        String status,
        long submitMs,
        long wallMs,
        Long queueWaitMs,
        Long serverDurationMs,
        long tokens,
        long costMicros,
        String stopReason,
        String error,
        List<String> phases,
        boolean infraError) {

    public TaskOutcome {
        phases = phases == null ? List.of() : List.copyOf(phases);
    }

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }

    public static TaskOutcome infraError(int index, String question, long wallMs, String error) {
        return new TaskOutcome(index, null, question, "ERROR", 0, wallMs, null, null,
                0, 0, null, error, List.of(), true);
    }
}
