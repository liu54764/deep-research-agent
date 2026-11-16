package com.dra.worker.activity;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.model.EventTypes;
import com.dra.domain.port.ProgressStream;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EventActivitiesImpl implements ResearchActivities.Events {

    private static final int MAX_WARNINGS_IN_STATE = 20;

    private final ProgressStream progressStream;

    public EventActivitiesImpl(ProgressStream progressStream) {
        this.progressStream = progressStream;
    }

    @Override
    public void emit(TaskEvent event) {
        TaskEvent stamped = event.withMetadata();
        progressStream.emit(stamped);
        progressStream.updateState(stamped.taskId(), stateOf(stamped));
    }

    private Map<String, String> stateOf(TaskEvent event) {
        Map<String, Object> payload = event.payload();
        Map<String, String> state = new LinkedHashMap<>();
        state.put("updatedAt", Instant.ofEpochMilli(event.ts()).toString());
        switch (event.type()) {
            case EventTypes.PHASE -> {
                state.put("phase", str(payload, "phase"));
                state.put("round", str(payload, "round"));
                if (!"DONE".equals(str(payload, "phase"))) {
                    state.put("status", "RUNNING");
                }
            }
            case EventTypes.PROGRESS -> {
                state.put("phase", str(payload, "phase"));
                state.put("done", str(payload, "done"));
                state.put("total", str(payload, "total"));
                state.put("percent", str(payload, "percent"));
            }
            case EventTypes.WARNING -> {
                String warning = str(payload, "code") + ": " + str(payload, "message");
                state.put("lastWarning", warning);
                state.put("warnings", appendWarning(event.taskId(), warning));
            }
            case EventTypes.PAUSED -> {
                state.put("status", "PAUSED");
                state.put("phase", str(payload, "phase"));
            }
            case EventTypes.RESUMED -> state.put("status", "RUNNING");
            case EventTypes.DONE -> {
                state.put("status", "SUCCEEDED");
                state.put("phase", "DONE");
                state.put("reportId", str(payload, "reportId"));
                state.put("stopReason", str(payload, "stopReason"));
            }
            case EventTypes.ERROR -> {
                String code = str(payload, "code");
                if ("CANCELED".equals(code)) {
                    state.put("status", "CANCELED");
                    state.put("phase", "CANCELED");
                    state.put("stopReason", "CANCELED");
                } else {
                    state.put("status", "FAILED");
                    state.put("errorCode", code);
                    state.put("errorMessage", str(payload, "message"));
                }
            }
            default -> {
                // source/evidence/claim events only need the timestamp
            }
        }
        return state;
    }

    private String appendWarning(String taskId, String warning) {
        // The workflow emits warnings sequentially, so a read-modify-write is safe here and
        // lets status expose the full list instead of only the last warning.
        String existing = progressStream.snapshot(taskId).getOrDefault("warnings", "");
        List<String> warnings = new ArrayList<>();
        if (!existing.isBlank()) {
            for (String value : existing.split("\n")) {
                if (!value.isBlank()) {
                    warnings.add(value);
                }
            }
        }
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
        if (warnings.size() > MAX_WARNINGS_IN_STATE) {
            warnings = warnings.subList(warnings.size() - MAX_WARNINGS_IN_STATE, warnings.size());
        }
        return String.join("\n", warnings);
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
