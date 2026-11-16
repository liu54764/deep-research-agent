package com.dra.bench;

import java.util.List;

/** Minimal API surface the load generator needs. */
public interface TaskClient {

    String submit(String idempotencyKey, String question, String depth);

    Status status(String taskId);

    record Status(
            String taskId,
            String status,
            String phase,
            Integer round,
            Usage usage,
            List<String> warnings,
            String stopReason,
            String errorCode,
            String errorMessage,
            String createdAt,
            String startedAt,
            String finishedAt) {

        public Status {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public long tokens() {
            return usage == null ? 0 : usage.tokens();
        }

        public long costMicros() {
            return usage == null ? 0 : usage.costMicros();
        }
    }

    record Usage(long tokens, long costMicros) {
    }
}
