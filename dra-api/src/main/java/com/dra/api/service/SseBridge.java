package com.dra.api.service;

import com.dra.contracts.dto.TaskEvent;
import com.dra.contracts.model.EventTypes;
import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.ProgressEvent;
import com.dra.domain.model.TaskRow;
import com.dra.domain.port.ProgressStream;
import com.dra.domain.port.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE relay over Redis Stream. Blocking XREAD on a virtual thread gives natural backpressure
 * (a slow client stalls its own thread, not the server).
 */
@Component
public class SseBridge {

    private final ProgressStream progressStream;
    private final TaskRepository tasks;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SseBridge(ProgressStream progressStream, TaskRepository tasks, ObjectMapper objectMapper) {
        this.progressStream = progressStream;
        this.tasks = tasks;
        this.objectMapper = objectMapper;
    }

    public SseEmitter open(String taskId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onError(error -> {
            // client disconnected; the pump thread exits on its next failed send
        });
        emitter.onTimeout(emitter::complete);
        executor.submit(() -> pump(emitter, taskId, lastEventId));
        return emitter;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private void pump(SseEmitter emitter, String taskId, String lastEventId) {
        String cursor = (lastEventId == null || lastEventId.isBlank()) ? "0-0" : lastEventId;
        try {
            Map<String, String> snapshot = progressStream.snapshot(taskId);
            if (!snapshot.isEmpty()) {
                TaskEvent event = TaskEvent.of(taskId, EventTypes.SNAPSHOT,
                        new LinkedHashMap<>(snapshot)).withMetadata();
                send(emitter, event);
            }

            int idle = 0;
            while (true) {
                List<ProgressEvent> events = progressStream.read(taskId, cursor, Duration.ofSeconds(15), 50);
                if (events.isEmpty()) {
                    idle++;
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                    TaskRow row = tasks.findById(taskId).orElse(null);
                    if (idle >= 2 && row != null && row.status().terminal()) {
                        sendTerminalFromDb(emitter, row);
                        break;
                    }
                    continue;
                }
                idle = 0;
                boolean terminal = false;
                for (ProgressEvent progressEvent : events) {
                    cursor = progressEvent.streamId();
                    send(emitter, progressEvent.event());
                    if (EventTypes.DONE.equals(progressEvent.event().type())
                            || EventTypes.ERROR.equals(progressEvent.event().type())) {
                        terminal = true;
                        break;
                    }
                }
                if (terminal) {
                    break;
                }
            }
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendTerminalFromDb(SseEmitter emitter, TaskRow row) throws IOException {
        TaskEvent event;
        if (row.status() == TaskStatus.SUCCEEDED) {
            event = TaskEvent.done(row.id(), row.reportId(), row.stopReason());
        } else if (row.status() == TaskStatus.CANCELED) {
            event = TaskEvent.error(row.id(), "CANCELED", "task canceled");
        } else {
            event = TaskEvent.error(row.id(),
                    row.errorCode() == null ? "FAILED" : row.errorCode(),
                    row.errorMessage() == null ? "task failed" : row.errorMessage());
        }
        send(emitter, event.withMetadata());
    }

    private void send(SseEmitter emitter, TaskEvent event) throws IOException {
        TaskEvent stamped = event.eventId() == null ? event.withMetadata() : event;
        emitter.send(SseEmitter.event()
                .id(stamped.eventId())
                .name(stamped.type())
                .data(objectMapper.writeValueAsString(stamped)));
    }
}
