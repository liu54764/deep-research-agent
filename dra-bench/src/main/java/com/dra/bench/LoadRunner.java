package com.dra.bench;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Submits N tasks with C in-flight, polls until terminal, and aggregates client-side
 * latency plus server timestamps (queue wait / run duration). Poll-only: SSE load is a
 * separate scenario so the two numbers are not mixed.
 */
public final class LoadRunner {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELED");
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final TaskClient client;
    private final BenchOptions options;
    private final List<String> questions;

    public LoadRunner(TaskClient client, BenchOptions options, List<String> questions) {
        this.client = client;
        this.options = options;
        this.questions = List.copyOf(questions);
        if (this.questions.isEmpty()) {
            throw new IllegalArgumentException("question pool is empty");
        }
    }

    public BenchResult run() {
        String runId = RUN_ID.format(Instant.now());
        Instant startedAt = Instant.now();
        int tasks = options.getTasks();
        int workers = Math.min(options.getConcurrency(), tasks);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<TaskOutcome>> futures = new ArrayList<>(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                String question = questions.get(index % questions.size());
                futures.add(pool.submit(() -> runTask(runId, index, question)));
            }
            List<TaskOutcome> outcomes = new ArrayList<>(tasks);
            for (Future<TaskOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add(TaskOutcome.infraError(outcomes.size(), "", 0, "interrupted"));
                } catch (ExecutionException e) {
                    outcomes.add(TaskOutcome.infraError(outcomes.size(), "", 0,
                            String.valueOf(e.getCause())));
                }
            }
            Instant finishedAt = Instant.now();
            long durationMs = Duration.between(startedAt, finishedAt).toMillis();
            BenchMetrics metrics = BenchMetrics.of(outcomes, durationMs);
            return new BenchResult(runId, startedAt, finishedAt, outcomes, metrics);
        } finally {
            pool.shutdown();
        }
    }

    private TaskOutcome runTask(String runId, int index, String question) {
        long rampMs = (long) options.getRampSeconds() * 1000L * index / Math.max(1, options.getTasks());
        if (rampMs > 0) {
            try {
                Thread.sleep(rampMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new TaskOutcome(index, null, question, "INTERRUPTED", 0, 0,
                        null, null, 0, 0, null, "interrupted before submit", List.of(), false);
            }
        }

        long startNanos = System.nanoTime();
        String taskId;
        try {
            taskId = client.submit("bench:" + runId + ":" + index, question, options.getDepth());
        } catch (RuntimeException e) {
            return TaskOutcome.infraError(index, question, elapsedMs(startNanos), e.getMessage());
        }
        long submitMs = elapsedMs(startNanos);

        LinkedHashSet<String> phases = new LinkedHashSet<>();
        long deadline = System.nanoTime()
                + Duration.ofMinutes(options.getTimeoutMinutes()).toNanos();
        TaskClient.Status status = null;
        while (true) {
            try {
                status = client.status(taskId);
            } catch (RuntimeException e) {
                return TaskOutcome.infraError(index, question, elapsedMs(startNanos),
                        "status failed: " + e.getMessage());
            }
            if (status.phase() != null && !status.phase().isBlank()) {
                phases.add(status.phase());
            }
            if (TERMINAL.contains(status.status())) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                return outcome(index, taskId, question, "TIMEOUT", submitMs,
                        elapsedMs(startNanos), status, phases, "timeout after "
                                + options.getTimeoutMinutes() + " min");
            }
            try {
                Thread.sleep(options.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return outcome(index, taskId, question, "INTERRUPTED", submitMs,
                        elapsedMs(startNanos), status, phases, "interrupted while polling");
            }
        }
        return outcome(index, taskId, question, status.status(), submitMs,
                elapsedMs(startNanos), status, phases, errorOf(status));
    }

    private TaskOutcome outcome(int index, String taskId, String question, String status,
                                long submitMs, long wallMs, TaskClient.Status observed,
                                LinkedHashSet<String> phases, String error) {
        Instant created = parse(observed.createdAt());
        Instant started = parse(observed.startedAt());
        Instant finished = parse(observed.finishedAt());
        Long queueWait = created != null && started != null
                ? Duration.between(created, started).toMillis() : null;
        Long serverDuration = started != null && finished != null
                ? Duration.between(started, finished).toMillis() : null;
        return new TaskOutcome(index, taskId, question, status, submitMs, wallMs,
                queueWait, serverDuration, observed.tokens(), observed.costMicros(),
                observed.stopReason(), error, List.copyOf(phases), false);
    }

    private static String errorOf(TaskClient.Status status) {
        if (status.errorCode() == null && status.errorMessage() == null) {
            return null;
        }
        return (status.errorCode() == null ? "" : status.errorCode() + ": ")
                + (status.errorMessage() == null ? "" : status.errorMessage());
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    public record BenchResult(String runId, Instant startedAt, Instant finishedAt,
                              List<TaskOutcome> outcomes, BenchMetrics metrics) {
    }
}
