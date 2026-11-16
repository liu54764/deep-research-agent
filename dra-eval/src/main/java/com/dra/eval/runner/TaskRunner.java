package com.dra.eval.runner;

import com.dra.eval.config.EvalProperties;
import com.dra.eval.dataset.EvalQuestion;
import com.dra.eval.support.EvalInfraException;
import com.dra.contracts.dto.ReportDocument;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Black-box task driver: submit via REST, poll status, fetch the structured report.
 * Deliberately knows nothing about Temporal or the worker modules.
 */
@Component
public class TaskRunner {

    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "CANCELED");

    private final EvalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;

    public TaskRunner(EvalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = trimTrailingSlash(properties.getApiBaseUrl());
    }

    public String submit(String runId, EvalQuestion question, String depth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question.question());
        body.put("depth", depth);
        body.put("language", "auto");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/research/tasks"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", properties.getApiKey())
                .header("Idempotency-Key", idempotencyKey(runId, question.id()))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        JsonNode response = send(request);
        String taskId = response.path("taskId").asText("");
        if (taskId.isBlank()) {
            throw new EvalInfraException("submit returned no taskId: " + response);
        }
        return taskId;
    }

    public Status awaitTerminal(String taskId) {
        long deadline = System.nanoTime() + Duration.ofMinutes(properties.getTaskTimeoutMinutes()).toNanos();
        while (true) {
            Status status = status(taskId);
            if (TERMINAL.contains(status.status())) {
                return status;
            }
            if (System.nanoTime() >= deadline) {
                throw new EvalInfraException("task " + taskId + " did not finish within "
                        + properties.getTaskTimeoutMinutes() + " min (last status " + status.status() + ")");
            }
            sleep(properties.getPollIntervalMs());
        }
    }

    public Status status(String taskId) {
        HttpRequest request = request("/api/v1/research/tasks/" + taskId)
                .GET()
                .build();
        return convert(send(request), Status.class);
    }

    public ReportEnvelope fetchReport(String taskId) {
        HttpRequest request = request("/api/v1/research/tasks/" + taskId + "/report")
                .GET()
                .build();
        return convert(send(request), ReportEnvelope.class);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("X-API-Key", properties.getApiKey());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new EvalInfraException("HTTP " + response.statusCode() + " from "
                        + request.uri().getPath() + ": " + truncate(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            throw new EvalInfraException("call failed: " + request.uri() + " (" + reason + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalInfraException("interrupted while calling " + request.uri(), e);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (IOException e) {
            throw new EvalInfraException("cannot parse " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize request", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvalInfraException("interrupted while polling", e);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String idempotencyKey(String runId, String questionId) {
        return "eval:" + runId + ":" + questionId;
    }

    /** Subset of the API status response the evaluator needs. */
    public record Status(
            String taskId,
            String status,
            String phase,
            Integer round,
            Usage usage,
            List<String> warnings,
            String stopReason,
            String reportId,
            String errorCode,
            String errorMessage) {

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

    public record Usage(long tokens, long costMicros) {
    }

    public record ReportEnvelope(String taskId, int version, String markdown, ReportDocument document) {
    }
}
