package com.dra.domain.port;

import com.dra.contracts.dto.TaskEvent;
import com.dra.domain.model.ProgressEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Progress transport: Redis Stream for replayable SSE, plus a latest-state snapshot hash. */
public interface ProgressStream {

    void emit(TaskEvent event);

    void updateState(String taskId, Map<String, String> fields);

    Map<String, String> snapshot(String taskId);

    /** Blocking read; returns an empty list when the block timeout elapses. */
    List<ProgressEvent> read(String taskId, String lastEventId, Duration block, int count);
}
