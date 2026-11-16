package com.dra.bench;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadRunnerTest {

    @Test
    void completesAllTasksAndAggregatesServerTimings() {
        BenchOptions options = BenchOptions.parse(new String[]{
                "--tasks=3", "--concurrency=2", "--poll-interval-ms=100", "--timeout-minutes=1"});
        LoadRunner runner = new LoadRunner(new FakeClient(), options, List.of("q1", "q2"));

        LoadRunner.BenchResult result = runner.run();

        assertEquals(3, result.outcomes().size());
        assertEquals(3, result.metrics().succeeded());
        assertEquals(1.0, result.metrics().successRate(), 1e-9);
        assertEquals(1000, result.metrics().queueP50Ms());
        assertEquals(2000, result.metrics().serverP50Ms());
        assertTrue(result.metrics().stopReasons().containsKey("COVERAGE_REACHED"));
        assertTrue(result.outcomes().stream()
                .allMatch(o -> o.phases().contains("PLAN") && o.phases().contains("DONE")));
    }

    @Test
    void submitFailureIsCountedAsInfraError() {
        BenchOptions options = BenchOptions.parse(new String[]{
                "--tasks=2", "--concurrency=1", "--poll-interval-ms=100"});
        TaskClient failing = new TaskClient() {
            @Override
            public String submit(String idempotencyKey, String question, String depth) {
                throw new HttpTaskClient.BenchInfraException("connection refused");
            }

            @Override
            public Status status(String taskId) {
                throw new IllegalStateException("unreachable");
            }
        };

        LoadRunner.BenchResult result = new LoadRunner(failing, options, List.of("q")).run();

        assertEquals(2, result.metrics().infraErrors());
        assertEquals(0, result.metrics().succeeded());
    }

    @Test
    void emptyQuestionPoolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoadRunner(new FakeClient(), BenchOptions.parse(new String[]{}), List.of()));
    }

    private static final class FakeClient implements TaskClient {
        private final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();

        @Override
        public String submit(String idempotencyKey, String question, String depth) {
            String taskId = "t-" + idempotencyKey.substring(idempotencyKey.lastIndexOf(':') + 1);
            polls.put(taskId, new AtomicInteger());
            return taskId;
        }

        @Override
        public Status status(String taskId) {
            int poll = polls.get(taskId).incrementAndGet();
            if (poll <= 2) {
                return new Status(taskId, "RUNNING", "PLAN", 1,
                        new Usage(0, 0), List.of(), null, null, null,
                        "2026-11-15T00:00:00Z", null, null);
            }
            return new Status(taskId, "SUCCEEDED", "DONE", 1,
                    new Usage(500, 10), List.of(), "COVERAGE_REACHED", null, null,
                    "2026-11-15T00:00:00Z", "2026-11-15T00:00:01Z", "2026-11-15T00:00:03Z");
        }
    }
}
