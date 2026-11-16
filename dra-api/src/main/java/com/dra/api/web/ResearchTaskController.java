package com.dra.api.web;

import com.dra.api.service.ResearchTaskService;
import com.dra.api.service.SseBridge;
import com.dra.api.web.dto.ReportResponse;
import com.dra.api.web.dto.SubmitTaskRequest;
import com.dra.api.web.dto.SubmitTaskResponse;
import com.dra.api.web.dto.TaskStatusResponse;
import com.dra.api.web.dto.TaskSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/research/tasks")
public class ResearchTaskController {

    private final ResearchTaskService service;
    private final SseBridge sseBridge;

    public ResearchTaskController(ResearchTaskService service, SseBridge sseBridge) {
        this.service = service;
        this.sseBridge = sseBridge;
    }

    @PostMapping
    public ResponseEntity<SubmitTaskResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitTaskRequest request) {
        return ResponseEntity.accepted().body(service.submit(idempotencyKey, request));
    }

    @GetMapping
    public List<TaskSummaryResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{taskId}")
    public TaskStatusResponse get(@PathVariable String taskId) {
        return service.get(taskId);
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        service.requireTask(taskId);
        return sseBridge.open(taskId, lastEventId);
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<TaskStatusResponse> pause(@PathVariable String taskId) {
        return ResponseEntity.accepted().body(service.pause(taskId));
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<TaskStatusResponse> resume(@PathVariable String taskId) {
        return ResponseEntity.accepted().body(service.resume(taskId));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<TaskStatusResponse> cancel(@PathVariable String taskId) {
        return ResponseEntity.accepted().body(service.cancel(taskId));
    }

    @GetMapping("/{taskId}/report")
    public ResponseEntity<?> report(@PathVariable String taskId,
                                    @RequestParam(defaultValue = "json") String format) {
        ReportResponse report = service.report(taskId);
        if ("markdown".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(report.markdown());
        }
        return ResponseEntity.ok(report);
    }
}
