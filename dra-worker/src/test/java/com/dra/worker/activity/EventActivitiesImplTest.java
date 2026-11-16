package com.dra.worker.activity;

import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.model.EventTypes;
import com.dra.domain.model.ProgressEvent;
import com.dra.domain.port.ProgressStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventActivitiesImplTest {

    @Test
    void warningsAccumulateInStateAndKeepTheLastOne() {
        FakeStream stream = new FakeStream();
        EventActivitiesImpl activity = new EventActivitiesImpl(stream);

        activity.emit(warning("FETCH_FAILED", "https://a.test/1"));
        activity.emit(warning("EXTRACT_EMPTY", "https://b.test/2"));
        activity.emit(warning("FETCH_FAILED", "https://a.test/1"));

        Map<String, String> state = stream.snapshot("task-1");
        assertEquals("FETCH_FAILED: https://a.test/1",
                state.get("warnings").split("\n")[0]);
        assertEquals(2, state.get("warnings").split("\n").length, "duplicates are not repeated");
        assertEquals("FETCH_FAILED: https://a.test/1", state.get("lastWarning"));
    }

    @Test
    void warningListIsCapped() {
        FakeStream stream = new FakeStream();
        EventActivitiesImpl activity = new EventActivitiesImpl(stream);

        for (int i = 0; i < 25; i++) {
            activity.emit(warning("FETCH_FAILED", "https://a.test/" + i));
        }

        String[] lines = stream.snapshot("task-1").get("warnings").split("\n");
        assertEquals(20, lines.length);
        assertEquals("FETCH_FAILED: https://a.test/24", lines[19]);
    }

    private static TaskEvent warning(String code, String message) {
        return TaskEvent.of("task-1", EventTypes.WARNING,
                Map.of("code", code, "message", message));
    }

    private static final class FakeStream implements ProgressStream {
        private final Map<String, Map<String, String>> states = new LinkedHashMap<>();
        private final List<TaskEvent> events = new ArrayList<>();

        @Override
        public void emit(TaskEvent event) {
            events.add(event);
        }

        @Override
        public void updateState(String taskId, Map<String, String> fields) {
            states.computeIfAbsent(taskId, id -> new LinkedHashMap<>()).putAll(fields);
        }

        @Override
        public Map<String, String> snapshot(String taskId) {
            return Map.copyOf(states.getOrDefault(taskId, Map.of()));
        }

        @Override
        public List<ProgressEvent> read(String taskId, String lastEventId, Duration block, int count) {
            return List.of();
        }
    }
}
