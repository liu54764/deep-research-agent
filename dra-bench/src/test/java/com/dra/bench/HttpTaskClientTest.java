package com.dra.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTaskClientTest {

    private HttpServer server;
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private HttpTaskClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new HttpTaskClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void submitsAndReadsStatus() {
        String taskId = client.submit("bench:run:0", "问题", "QUICK");
        TaskClient.Status running = client.status(taskId);
        TaskClient.Status done = client.status(taskId);

        assertEquals("t-1", taskId);
        assertEquals("bench:run:0", idempotencyKey.get());
        assertEquals("RUNNING", running.status());
        assertEquals("SUCCEEDED", done.status());
        assertEquals(1234, done.tokens());
        assertEquals(42, done.costMicros());
        assertEquals("COVERAGE_REACHED", done.stopReason());
        assertTrue(polls.get() >= 2);
    }

    @Test
    void non2xxIsAnInfraError() {
        HttpTaskClient failing = new HttpTaskClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/broken",
                "k", new ObjectMapper());

        assertThrows(HttpTaskClient.BenchInfraException.class,
                () -> failing.submit("idem", "q", "QUICK"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/research/tasks".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 202, "{\"taskId\":\"t-1\",\"status\":\"QUEUED\"}");
            return;
        }
        if ("/api/v1/research/tasks/t-1".equals(path)) {
            int poll = polls.incrementAndGet();
            if (poll == 1) {
                respond(exchange, 200, "{\"taskId\":\"t-1\",\"status\":\"RUNNING\",\"phase\":\"PLAN\"}");
            } else {
                respond(exchange, 200, """
                        {"taskId":"t-1","status":"SUCCEEDED","phase":"DONE",
                         "usage":{"tokens":1234,"costMicros":42},
                         "stopReason":"COVERAGE_REACHED",
                         "createdAt":"2026-11-15T00:00:00Z","startedAt":"2026-11-15T00:00:01Z",
                         "finishedAt":"2026-11-15T00:00:03Z"}
                        """);
            }
            return;
        }
        respond(exchange, 500, "{\"error\":\"boom\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
