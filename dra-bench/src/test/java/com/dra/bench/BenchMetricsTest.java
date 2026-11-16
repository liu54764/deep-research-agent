package com.dra.bench;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchMetricsTest {

    @Test
    void percentileUsesNearestRank() {
        List<Long> values = new ArrayList<>(List.of(100L, 200L, 300L, 400L, 500L));

        assertEquals(300, BenchMetrics.percentile(values, 0.50));
        assertEquals(500, BenchMetrics.percentile(values, 0.95));
        assertEquals(0, BenchMetrics.percentile(List.of(), 0.95));
    }

    @Test
    void aggregatesStatusAndTimings() {
        List<TaskOutcome> outcomes = List.of(
                outcome("SUCCEEDED", 1000, 900, 5000L, 3000L, 100, 10, "COVERAGE_REACHED"),
                outcome("SUCCEEDED", 2000, 800, 6000L, 4000L, 200, 20, "COVERAGE_REACHED"),
                outcome("FAILED", 3000, 700, null, null, 0, 0, null));

        BenchMetrics metrics = BenchMetrics.of(outcomes, 60_000);

        assertEquals(3, metrics.tasks());
        assertEquals(2, metrics.succeeded());
        assertEquals(1, metrics.failed());
        assertEquals(2.0 / 3.0, metrics.successRate(), 1e-9);
        assertEquals(1000, metrics.wallP50Ms());
        assertEquals(5000, metrics.queueP50Ms());
        assertEquals(3000, metrics.serverP50Ms());
        assertEquals(300, metrics.tokens());
        assertEquals(30, metrics.costMicros());
        assertEquals(2.0, metrics.throughputPerMinute(), 1e-9);
        assertEquals(2, metrics.stopReasons().get("COVERAGE_REACHED"));
    }

    @Test
    void countsInfraErrors() {
        List<TaskOutcome> outcomes = List.of(
                TaskOutcome.infraError(0, "q", 100, "connection refused"));

        BenchMetrics metrics = BenchMetrics.of(outcomes, 100);

        assertEquals(1, metrics.infraErrors());
        assertEquals(0, metrics.succeeded());
        assertEquals(0.0, metrics.successRate(), 1e-9);
    }

    @Test
    void phasePresenceCountsTasksNotPolls() {
        TaskOutcome outcome = new TaskOutcome(0, "t", "q", "SUCCEEDED", 10, 20,
                5L, 10L, 0, 0, "COVERAGE_REACHED", null,
                List.of("PLAN", "REPORT"), false);

        assertEquals(1, BenchMetrics.phasePresence(List.of(outcome)).get("PLAN"));
    }

    private static TaskOutcome outcome(String status, long wall, long submit, Long queue,
                                       Long server, long tokens, long cost, String stop) {
        return new TaskOutcome(0, "t", "q", status, submit, wall, queue, server,
                tokens, cost, stop, null, List.of(), false);
    }
}
