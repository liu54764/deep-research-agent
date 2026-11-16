package com.dra.contracts.dto;

import com.dra.common.id.Ids;
import com.dra.contracts.model.EventTypes;
import com.dra.contracts.model.Phase;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Progress event written to the Redis Stream and relayed over SSE.
 * Workflows build these without IDs/timestamps (determinism); the event activity
 * stamps them via {@link #withMetadata()}.
 */
public record TaskEvent(
        String eventId,
        String taskId,
        String type,
        long ts,
        Map<String, Object> payload) {

    public TaskEvent {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Called from an activity (non-deterministic context) to fill id/timestamp. */
    public TaskEvent withMetadata() {
        return new TaskEvent(
                eventId == null ? Ids.ulid() : eventId,
                taskId,
                type,
                ts > 0 ? ts : System.currentTimeMillis(),
                payload);
    }

    public static TaskEvent phase(String taskId, Phase phase, int round) {
        return of(taskId, EventTypes.PHASE, payload("phase", phase.name(), "round", round));
    }

    public static TaskEvent progress(String taskId, Phase phase, int done, int total) {
        int percent = total <= 0 ? 0 : (int) Math.min(100, Math.round(done * 100.0 / total));
        return of(taskId, EventTypes.PROGRESS,
                payload("phase", phase.name(), "done", done, "total", total, "percent", percent));
    }

    public static TaskEvent source(String taskId, String url, String title, String status) {
        return of(taskId, EventTypes.SOURCE,
                payload("url", url, "title", title == null ? "" : title, "status", status));
    }

    public static TaskEvent evidence(String taskId, String subQuestionId, int count) {
        return of(taskId, EventTypes.EVIDENCE, payload("subQuestionId", subQuestionId, "count", count));
    }

    public static TaskEvent warning(String taskId, String code, String message) {
        return of(taskId, EventTypes.WARNING, payload("code", code, "message", message == null ? "" : message));
    }

    public static TaskEvent done(String taskId, String reportId, String stopReason) {
        return of(taskId, EventTypes.DONE, payload(
                "reportId", reportId == null ? "" : reportId,
                "stopReason", stopReason == null ? "" : stopReason));
    }

    public static TaskEvent error(String taskId, String code, String message) {
        return of(taskId, EventTypes.ERROR, payload("code", code, "message", message == null ? "" : message));
    }

    public static TaskEvent of(String taskId, String type, Map<String, Object> payload) {
        return new TaskEvent(null, taskId, type, 0L, payload);
    }

    private static Map<String, Object> payload(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("payload requires key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
