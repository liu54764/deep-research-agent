package com.dra.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregated load-test numbers; percentiles are nearest-rank over completed tasks. */
public record BenchMetrics(
        int tasks,
        int succeeded,
        int failed,
        int canceled,
        int infraErrors,
        double successRate,
        long wallP50Ms,
        long wallP95Ms,
        long wallP99Ms,
        long submitP50Ms,
        long submitP95Ms,
        long queueP50Ms,
        long queueP95Ms,
        long serverP50Ms,
        long serverP95Ms,
        long tokens,
        long costMicros,
        double throughputPerMinute,
        Map<String, Integer> statusCounts,
        Map<String, Integer> stopReasons,
        long durationMs) {

    public BenchMetrics {
        statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
        stopReasons = stopReasons == null ? Map.of() : Map.copyOf(stopReasons);
    }

    public static BenchMetrics of(List<TaskOutcome> outcomes, long durationMs) {
        List<Long> walls = new ArrayList<>();
        List<Long> submits = new ArrayList<>();
        List<Long> queue = new ArrayList<>();
        List<Long> server = new ArrayList<>();
        Map<String, Integer> statusCounts = new TreeMap<>();
        Map<String, Integer> stopReasons = new TreeMap<>();
        int succeeded = 0;
        int infraErrors = 0;
        long tokens = 0;
        long cost = 0;

        for (TaskOutcome outcome : outcomes) {
            statusCounts.merge(outcome.status(), 1, Integer::sum);
            if (outcome.succeeded()) {
                succeeded++;
                walls.add(outcome.wallMs());
                submits.add(outcome.submitMs());
                if (outcome.queueWaitMs() != null) {
                    queue.add(outcome.queueWaitMs());
                }
                if (outcome.serverDurationMs() != null) {
                    server.add(outcome.serverDurationMs());
                }
            }
            if (outcome.infraError()) {
                infraErrors++;
            }
            if (outcome.stopReason() != null && !outcome.stopReason().isBlank()) {
                stopReasons.merge(outcome.stopReason(), 1, Integer::sum);
            }
            tokens += outcome.tokens();
            cost += outcome.costMicros();
        }
        int failed = outcomes.size() - succeeded;
        int canceled = statusCounts.getOrDefault("CANCELED", 0);
        double successRate = outcomes.isEmpty() ? 0.0 : (double) succeeded / outcomes.size();
        double throughput = durationMs <= 0 ? 0.0 : succeeded * 60_000.0 / durationMs;

        return new BenchMetrics(
                outcomes.size(), succeeded, failed, canceled, infraErrors, successRate,
                percentile(walls, 0.50), percentile(walls, 0.95), percentile(walls, 0.99),
                percentile(submits, 0.50), percentile(submits, 0.95),
                percentile(queue, 0.50), percentile(queue, 0.95),
                percentile(server, 0.50), percentile(server, 0.95),
                tokens, cost, throughput, statusCounts, stopReasons, durationMs);
    }

    public static long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Distribution of first-seen phases across tasks (diagnostic, not a latency measure). */
    public static Map<String, Integer> phasePresence(List<TaskOutcome> outcomes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TaskOutcome outcome : outcomes) {
            for (String phase : outcome.phases()) {
                counts.merge(phase, 1, Integer::sum);
            }
        }
        return counts;
    }
}
