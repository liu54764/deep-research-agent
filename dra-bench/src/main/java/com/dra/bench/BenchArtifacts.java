package com.dra.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes run.json / metrics.json / tasks.jsonl / summary.md under {@code <out>/<runId>}. */
public final class BenchArtifacts {

    private final ObjectMapper mapper;

    public BenchArtifacts(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Path write(LoadRunner.BenchResult result, BenchOptions options) {
        Path dir = Path.of(options.getOut(), result.runId());
        try {
            Files.createDirectories(dir);
            writeJson(dir.resolve("run.json"), manifest(result, options));
            writeJson(dir.resolve("metrics.json"), result.metrics());
            writeJsonLines(dir.resolve("tasks.jsonl"), result.outcomes());
            Files.writeString(dir.resolve("summary.md"), summary(result, options),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write load-test artifacts to " + dir, e);
        }
    }

    private Map<String, Object> manifest(LoadRunner.BenchResult result, BenchOptions options) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", result.runId());
        manifest.put("startedAt", result.startedAt().toString());
        manifest.put("finishedAt", result.finishedAt().toString());
        manifest.put("apiUrl", options.getApiUrl());
        manifest.put("apiKeySet", options.getApiKey() != null && !options.getApiKey().isBlank());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tasks", options.getTasks());
        config.put("concurrency", options.getConcurrency());
        config.put("depth", options.getDepth());
        config.put("pollIntervalMs", options.getPollIntervalMs());
        config.put("timeoutMinutes", options.getTimeoutMinutes());
        config.put("rampSeconds", options.getRampSeconds());
        config.put("questionsFile", options.getQuestionsPath());
        config.put("minSuccessRate", options.getMinSuccessRate());
        config.put("maxP95Seconds", options.getMaxP95Seconds());
        manifest.put("config", config);
        manifest.put("runtime", Map.of(
                "javaVersion", System.getProperty("java.version", ""),
                "os", System.getProperty("os.name", ""),
                "cores", Runtime.getRuntime().availableProcessors()));
        return manifest;
    }

    private String summary(LoadRunner.BenchResult result, BenchOptions options) {
        BenchMetrics m = result.metrics();
        StringBuilder md = new StringBuilder();
        md.append("# Load test ").append(result.runId()).append("\n\n");
        md.append("- api: `").append(options.getApiUrl()).append("`\n");
        md.append("- tasks: ").append(options.getTasks())
                .append(" · concurrency: ").append(options.getConcurrency())
                .append(" · depth: ").append(options.getDepth())
                .append(" · ramp: ").append(options.getRampSeconds()).append("s\n");
        md.append("- duration: ").append(seconds(m.durationMs())).append("\n\n");

        md.append("## Headline\n\n");
        md.append("| metric | value |\n|---|---|\n");
        md.append("| success rate | ").append(rate(m.successRate()))
                .append(" (").append(m.succeeded()).append("/").append(m.tasks()).append(") |\n");
        md.append("| throughput | ").append(String.format(Locale.ROOT, "%.2f", m.throughputPerMinute()))
                .append(" tasks/min |\n");
        md.append("| wall P50 / P95 / P99 | ").append(seconds(m.wallP50Ms())).append(" / ")
                .append(seconds(m.wallP95Ms())).append(" / ").append(seconds(m.wallP99Ms())).append(" |\n");
        md.append("| submit P50 / P95 | ").append(seconds(m.submitP50Ms())).append(" / ")
                .append(seconds(m.submitP95Ms())).append(" |\n");
        md.append("| queue wait P50 / P95 | ").append(seconds(m.queueP50Ms())).append(" / ")
                .append(seconds(m.queueP95Ms())).append(" |\n");
        md.append("| server run P50 / P95 | ").append(seconds(m.serverP50Ms())).append(" / ")
                .append(seconds(m.serverP95Ms())).append(" |\n");
        md.append("| tokens / cost | ").append(m.tokens()).append(" / ")
                .append(cny(m.costMicros())).append(" |\n");
        md.append("| infra errors | ").append(m.infraErrors()).append(" |\n\n");

        md.append("## Status\n\n| status | count |\n|---|---|\n");
        m.statusCounts().forEach((status, count) ->
                md.append("| ").append(status).append(" | ").append(count).append(" |\n"));
        md.append('\n');

        if (!m.stopReasons().isEmpty()) {
            md.append("## Stop reasons\n\n| reason | count |\n|---|---|\n");
            m.stopReasons().forEach((reason, count) ->
                    md.append("| ").append(reason).append(" | ").append(count).append(" |\n"));
            md.append('\n');
        }

        Map<String, Integer> phases = BenchMetrics.phasePresence(result.outcomes());
        if (!phases.isEmpty()) {
            md.append("## Phases (first-seen counts, diagnostic)\n\n| phase | tasks |\n|---|---|\n");
            phases.forEach((phase, count) ->
                    md.append("| ").append(phase).append(" | ").append(count).append(" |\n"));
            md.append('\n');
        }

        md.append("## Per task\n\n");
        md.append("| # | status | wall | submit | queue | server | tokens | stop | error |\n");
        md.append("|---|---|---|---|---|---|---|---|---|\n");
        for (TaskOutcome outcome : result.outcomes()) {
            md.append("| ").append(outcome.index())
                    .append(" | ").append(outcome.status())
                    .append(" | ").append(seconds(outcome.wallMs()))
                    .append(" | ").append(seconds(outcome.submitMs()))
                    .append(" | ").append(seconds(outcome.queueWaitMs()))
                    .append(" | ").append(seconds(outcome.serverDurationMs()))
                    .append(" | ").append(outcome.tokens())
                    .append(" | ").append(outcome.stopReason() == null ? "-" : outcome.stopReason())
                    .append(" | ").append(outcome.error() == null ? "-" : escape(outcome.error()))
                    .append(" |\n");
        }
        md.append('\n');

        md.append("## Gate\n\n");
        md.append("- success rate ≥ ").append(rate(options.getMinSuccessRate())).append(" → ")
                .append(m.successRate() >= options.getMinSuccessRate() ? "PASS" : "FAIL").append("\n");
        if (options.getMaxP95Seconds() > 0) {
            md.append("- wall P95 ≤ ").append(seconds((long) (options.getMaxP95Seconds() * 1000)))
                    .append(" → ").append(m.wallP95Ms() <= options.getMaxP95Seconds() * 1000
                            ? "PASS" : "FAIL").append("\n");
        } else {
            md.append("- wall P95 gate disabled\n");
        }
        md.append('\n');
        md.append("> Poll-only driver: SSE concurrency is measured separately so the two numbers stay comparable.\n");
        return md.toString();
    }

    private void writeJson(Path path, Object value) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private void writeJsonLines(Path path, List<TaskOutcome> outcomes) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (TaskOutcome outcome : outcomes) {
            sb.append(mapper.writeValueAsString(outcome)).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String seconds(Long millis) {
        if (millis == null || millis <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String cny(long micros) {
        return micros <= 0 ? "-" : String.format(Locale.ROOT, "¥%.4f", micros / 1_000_000.0);
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
