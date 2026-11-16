package com.dra.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CLI entrypoint: parse flags, run the load, write artifacts, gate the exit code. */
public final class BenchApplication {

    public static final int EXIT_OK = 0;
    public static final int EXIT_GATE = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_INFRA = 3;

    public static void main(String[] args) {
        BenchOptions options;
        try {
            options = BenchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("invalid arguments: " + e.getMessage());
            System.err.println(BenchOptions.usage());
            System.exit(EXIT_USAGE);
            return;
        }
        if (options.isHelp()) {
            System.out.println(BenchOptions.usage());
            return;
        }

        List<String> questions;
        try {
            questions = loadQuestions(options);
        } catch (IOException e) {
            System.err.println("cannot read questions: " + e.getMessage());
            System.exit(EXIT_USAGE);
            return;
        }

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TaskClient client = new HttpTaskClient(options.getApiUrl(), options.getApiKey(), mapper);
        LoadRunner.BenchResult result;
        try {
            result = new LoadRunner(client, options, questions).run();
        } catch (RuntimeException e) {
            System.err.println("load test aborted: " + e.getMessage());
            System.exit(EXIT_INFRA);
            return;
        }

        Path dir = new BenchArtifacts(mapper).write(result, options);
        BenchMetrics metrics = result.metrics();
        System.out.println(String.format(Locale.ROOT,
                "load test %s: %d/%d succeeded (%.3f), wall P50/P95 %.1fs/%.1fs, "
                        + "throughput %.2f tasks/min, infra errors %d",
                result.runId(), metrics.succeeded(), metrics.tasks(), metrics.successRate(),
                metrics.wallP50Ms() / 1000.0, metrics.wallP95Ms() / 1000.0,
                metrics.throughputPerMinute(), metrics.infraErrors()));
        System.out.println("artifacts: " + dir.toAbsolutePath());
        System.exit(gate(metrics, options));
    }

    static int gate(BenchMetrics metrics, BenchOptions options) {
        if (metrics.infraErrors() > 0) {
            return EXIT_INFRA;
        }
        if (metrics.successRate() < options.getMinSuccessRate()) {
            System.err.println("gate failed: success rate " + metrics.successRate()
                    + " < " + options.getMinSuccessRate());
            return EXIT_GATE;
        }
        if (options.getMaxP95Seconds() > 0
                && metrics.wallP95Ms() > options.getMaxP95Seconds() * 1000) {
            System.err.println("gate failed: wall P95 " + metrics.wallP95Ms()
                    + "ms > " + (long) (options.getMaxP95Seconds() * 1000) + "ms");
            return EXIT_GATE;
        }
        return EXIT_OK;
    }

    static List<String> loadQuestions(BenchOptions options) throws IOException {
        Path file = options.getQuestionsPath() == null || options.getQuestionsPath().isBlank()
                ? null : Path.of(options.getQuestionsPath());
        if (file == null) {
            return BenchOptions.defaultQuestions();
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("file not found: " + file.toAbsolutePath());
        }
        List<String> questions = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                questions.add(trimmed);
            }
        }
        if (questions.isEmpty()) {
            throw new IOException("no questions in " + file.toAbsolutePath());
        }
        return questions;
    }
}
