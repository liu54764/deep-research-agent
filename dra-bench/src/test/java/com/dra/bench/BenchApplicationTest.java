package com.dra.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchApplicationTest {

    @Test
    void passesWhenGatesAreMet() {
        assertEquals(BenchApplication.EXIT_OK,
                BenchApplication.gate(metrics(10, 10, 0, 100_000), options(0.98, 300)));
    }

    @Test
    void failsOnSuccessRate() {
        assertEquals(BenchApplication.EXIT_GATE,
                BenchApplication.gate(metrics(9, 10, 0, 10_000), options(0.98, 300)));
    }

    @Test
    void failsOnP95Gate() {
        assertEquals(BenchApplication.EXIT_GATE,
                BenchApplication.gate(metrics(10, 10, 0, 400_000), options(0.98, 300)));
    }

    @Test
    void p95GateCanBeDisabled() {
        assertEquals(BenchApplication.EXIT_OK,
                BenchApplication.gate(metrics(10, 10, 0, 400_000), options(0.98, 0)));
    }

    @Test
    void infraErrorsWinOverGates() {
        assertEquals(BenchApplication.EXIT_INFRA,
                BenchApplication.gate(metrics(10, 10, 1, 10_000), options(0.98, 300)));
    }

    @Test
    void loadsQuestionsSkippingCommentsAndBlanks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("questions.txt");
        Files.writeString(file, "# comment\n\n第一个问题\n第二个问题\n", StandardCharsets.UTF_8);

        BenchOptions options = BenchOptions.parse(new String[]{"--questions=" + file});

        List<String> questions = BenchApplication.loadQuestions(options);

        assertEquals(List.of("第一个问题", "第二个问题"), questions);
    }

    @Test
    void missingQuestionsFileIsAnError() {
        BenchOptions options = BenchOptions.parse(new String[]{"--questions=does-not-exist.txt"});

        assertThrows(IOException.class, () -> BenchApplication.loadQuestions(options));
    }

    @Test
    void defaultsToBuiltInQuestions() throws IOException {
        List<String> questions = BenchApplication.loadQuestions(BenchOptions.parse(new String[]{}));

        assertEquals(BenchOptions.defaultQuestions().size(), questions.size());
    }

    private static BenchOptions options(double minSuccess, double maxP95) {
        return BenchOptions.parse(new String[]{
                "--min-success-rate=" + minSuccess,
                "--max-p95-seconds=" + maxP95});
    }

    private static BenchMetrics metrics(int succeeded, int total, int infra, long wallMs) {
        List<TaskOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            boolean ok = i < succeeded;
            outcomes.add(new TaskOutcome(i, "t" + i, "q", ok ? "SUCCEEDED" : "FAILED",
                    10, wallMs, 5L, wallMs, 0, 0, ok ? "COVERAGE_REACHED" : null,
                    null, List.of(), i < infra));
        }
        return BenchMetrics.of(outcomes, 60_000);
    }
}
