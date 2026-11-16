package com.dra.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineTest {

    private static final String SHA = "abc123";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void baselineRoundTrips(@TempDir Path dir) {
        Baseline baseline = baseline(0.90, 10_000, 1000, true);
        Path file = dir.resolve("baseline-v1.json");

        baseline.write(file, mapper);
        Baseline read = Baseline.read(file, mapper).orElseThrow();

        assertEquals(SHA, read.datasetSha256());
        assertEquals(0.90, read.metrics().carStrict(), 1e-9);
        assertEquals("QUICK", read.depth());
    }

    @Test
    void identicalRunHasNoRegression() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.90, 10_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "judge-x", true);

        assertTrue(diff.comparable());
        assertFalse(diff.regressed());
    }

    @Test
    void rateDropOfTenPointsIsARegression() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.80, 10_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "judge-x", true);

        assertTrue(diff.regressed());
        assertTrue(diff.regressions().stream().anyMatch(d -> d.metric().equals("carStrict")));
    }

    @Test
    void smallRateDropWithinToleranceIsNotARegression() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.900, 10_000, 1000, true),
                metrics(0.880, 10_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "judge-x", true);

        assertFalse(diff.regressed());
    }

    @Test
    void latencyIncreaseOverFifteenPercentIsARegression() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.90, 12_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "judge-x", true);

        assertTrue(diff.regressed());
        assertTrue(diff.regressions().stream().anyMatch(d -> d.metric().equals("latencyP50Ms")));
    }

    @Test
    void rescoreSkipsPerformanceMetrics() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.90, 0, 0, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "judge-x", false);

        assertFalse(diff.regressed());
        assertTrue(diff.deltas().stream().noneMatch(d -> d.metric().equals("latencyP50Ms")));
    }

    @Test
    void differentDatasetIsNotComparable() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.50, 10_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint("changed", List.of("2026-11-15")),
                "QUICK", true, "judge-x", true);

        assertFalse(diff.comparable());
        assertFalse(diff.regressed());
        assertTrue(diff.reason().contains("dataset"));
    }

    @Test
    void judgeConfigChangeIsNotComparable() {
        BaselineComparator.Diff diff = BaselineComparator.compare(
                baseline(0.90, 10_000, 1000, true),
                metrics(0.90, 10_000, 1000, 5, 5),
                new Baseline.DatasetFingerprint(SHA, List.of("2026-11-15")),
                "QUICK", true, "another-judge", true);

        assertFalse(diff.comparable());
        assertTrue(diff.reason().contains("judge model"));
    }

    private static Baseline baseline(double car, long p50, long cost, boolean judgeEnabled) {
        return new Baseline("v1", "2026-11-15T00:00:00Z", SHA, List.of("2026-11-15"), 8,
                "QUICK", judgeEnabled, "judge-x",
                new Baseline.Snapshot(car, car, 0.95, 1.0, 0.9, 0.0, 1.0, 1.0, 0.0,
                        p50, p50 + 1000, cost, 100, 1.0));
    }

    private static EvalMetrics metrics(double car, long p50, long cost,
                                       int citationsJudged, int claimsWithCitations) {
        return new EvalMetrics(
                8, 8, 0, 0,
                p50, p50 + 1000, cost, 10_000,
                10, citationsJudged,
                10, 10, 1.0,
                0, 0, 0, 0, 0.0,
                citationsJudged, (int) Math.round(car * citationsJudged), 0, 0, 0,
                claimsWithCitations, car, car, 1.0, 1.0,
                2, 2.0, 2.0, 0.9, 1,
                3, 0, 0.0, 1.0,
                citationsJudged, 0, 0, 0, 0,
                0, Map.of(), 4);
    }
}
