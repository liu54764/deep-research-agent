package com.dra.eval.runner;

import com.dra.eval.config.EvalProperties;
import com.dra.eval.dataset.EvalQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRunnerTest {

    private HttpServer server;
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> submitBody = new AtomicReference<>();
    private TaskRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        EvalProperties properties = new EvalProperties();
        properties.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test-key");
        properties.setPollIntervalMs(5);
        properties.setTaskTimeoutMinutes(1);
        runner = new TaskRunner(properties, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void submitsPollsAndFetchesReport() {
        String runId = "20261115T101500Z";
        EvalQuestion question = new EvalQuestion("q-a", "2026-11-15", "regression", "x",
                "what is the answer", "zh", "QUICK", null, false, "");

        String taskId = runner.submit(runId, question, "QUICK");
        TaskRunner.Status status = runner.awaitTerminal(taskId);
        TaskRunner.ReportEnvelope report = runner.fetchReport(taskId);

        assertEquals("t-1", taskId);
        assertEquals("SUCCEEDED", status.status());
        assertEquals(1234, status.tokens());
        assertEquals(42, status.costMicros());
        assertEquals(1, report.document().sections().size());
        assertEquals("the statement", report.document().sections().get(0).claims().get(0).statement());
        assertEquals("eval:" + runId + ":q-a", idempotencyKey.get());
        assertTrue(submitBody.get().contains("\"depth\":\"QUICK\""));
        assertTrue(polls.get() >= 2, "should poll until terminal");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/research/tasks".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            submitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"taskId\":\"t-1\",\"status\":\"QUEUED\"}");
            return;
        }
        if ("/api/v1/research/tasks/t-1".equals(path)) {
            int poll = polls.incrementAndGet();
            if (poll == 1) {
                respond(exchange, 200, "{\"taskId\":\"t-1\",\"status\":\"RUNNING\"}");
            } else {
                respond(exchange, 200, """
                        {"taskId":"t-1","status":"SUCCEEDED","warnings":["FETCH_UNSUPPORTED"],
                         "stopReason":"COVERAGE_REACHED","usage":{"tokens":1234,"costMicros":42}}
                        """);
            }
            return;
        }
        if ("/api/v1/research/tasks/t-1/report".equals(path)) {
            respond(exchange, 200, """
                    {"taskId":"t-1","version":1,"markdown":"# report",
                     "document":{"sections":[{"key":"s1","title":"Section","claims":[
                       {"id":"c1","statement":"the statement","status":"SUPPORTED","confidence":0.8,
                        "citations":[{"ref":"[1]","url":"https://example.com/x","title":"t","quote":"q"}]}]}],
                     "conflicts":[],"references":[{"n":1,"url":"https://example.com/x","title":"t","accessedAt":"2026-11-15"}],
                     "stats":{"stopReason":"COVERAGE_REACHED","durationMs":1000,"tokens":1234,"costMicros":42,
                              "sourcesFetched":1,"evidence":1,"claims":1,"verifiedRatio":1.0,"warnings":["FETCH_UNSUPPORTED"]},
                     "meta":{"stopReason":"COVERAGE_REACHED"}}}
                    """);
            return;
        }
        respond(exchange, 404, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
