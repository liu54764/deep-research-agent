package com.dra.bench;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** JDK HttpClient implementation: submit returns 202, status is a plain GET. */
public final class HttpTaskClient implements TaskClient {

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public HttpTaskClient(String baseUrl, String apiKey, ObjectMapper mapper) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.mapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String submit(String idempotencyKey, String question, String depth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("depth", depth);
        body.put("language", "auto");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/research/tasks"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
        SubmitResponse response = send(request, SubmitResponse.class);
        if (response == null || response.taskId() == null || response.taskId().isBlank()) {
            throw new BenchInfraException("submit returned no taskId");
        }
        return response.taskId();
    }

    @Override
    public Status status(String taskId) {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/research/tasks/" + taskId))
                .timeout(Duration.ofSeconds(30))
                .header("X-API-Key", apiKey)
                .GET()
                .build();
        Status status = send(request, Status.class);
        if (status == null) {
            throw new BenchInfraException("empty status for task " + taskId);
        }
        return status;
    }

    private <T> T send(HttpRequest request, Class<T> type) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new BenchInfraException("HTTP " + response.statusCode() + " from "
                        + request.uri().getPath() + ": " + truncate(response.body()));
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw new BenchInfraException("call failed: " + request.uri() + " (" + reason + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BenchInfraException("interrupted while calling " + request.uri(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serialize request", e);
        }
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    private record SubmitResponse(String taskId, String status) {
    }

    public static final class BenchInfraException extends RuntimeException {
        public BenchInfraException(String message) {
            super(message);
        }

        public BenchInfraException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
