package com.dra.eval.report;

import com.dra.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThresholdEvaluatorTest {

    @Test
    void passesWhenAllPresentMetricsMeetTargets() {
        EvalMetrics metrics = metrics(1, 1, 0.90, 10, 0.95, 0, 1.0, 0);

        ThresholdEvaluator.Result result =
                ThresholdEvaluator.evaluate(metrics, new EvalProperties.Thresholds(), true);

        assertTrue(result.enabled());
        assertTrue(result.passed());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void failsWhenCarOrGcrOrSuccessBelowTarget() {
        EvalMetrics metrics = metrics(2, 1, 0.50, 10, 0.70, 0, 0.60, 0);

        ThresholdEvaluator.Result result =
                ThresholdEvaluator.evaluate(metrics, new EvalProperties.Thresholds(), true);

        assertFalse(result.passed());
        assertEquals(3, result.failures().size(), result.messages().toString());
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("carStrict=0.500")));
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("gcr=0.700")));
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("successRate=0.500")));
    }

    /**
     * Gold coverage depends on how much budget the depth gives the planner, so the floor is
     * per depth. 0.632 is the measured QUICK value; it must not be judged by the DEEP floor.
     */
    @Test
    void gcrFloorDependsOnDepth() {
        EvalProperties.Thresholds spec = new EvalProperties.Thresholds();
        EvalMetrics quick = metrics(1, 1, 0.95, 10, 0.632, 0, 1.0, 0);

        assertTrue(ThresholdEvaluator.evaluate(quick, spec, true, "QUICK").passed(),
                "QUICK floor is 0.60");
        assertFalse(ThresholdEvaluator.evaluate(quick, spec, true, "STANDARD").passed(),
                "STANDARD floor is 0.75");
        assertFalse(ThresholdEvaluator.evaluate(quick, spec, true, "DEEP").passed(),
                "DEEP floor is 0.80");
    }

    @Test
    void explicitMinGcrOverridesTheDepthFloor() {
        EvalProperties.Thresholds spec = new EvalProperties.Thresholds();
        spec.setMinGcr(0.90);
        EvalMetrics quick = metrics(1, 1, 0.95, 10, 0.632, 0, 1.0, 0);

        assertFalse(ThresholdEvaluator.evaluate(quick, spec, true, "QUICK").passed());
    }

    @Test
    void contradictionRateIsAnUpperBound() {
        EvalMetrics metrics = metrics(1, 1, 10, 10, 1.0, 2, 1.0, 0.20);

        ThresholdEvaluator.Result result =
                ThresholdEvaluator.evaluate(metrics, new EvalProperties.Thresholds(), true);

        assertFalse(result.passed());
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("contradictionRate=0.200")));
    }

    @Test
    void absentJudgeMetricsAreSkipped() {
        // No citations and no key points were judged: CAR/HFR/GCR must not be treated as zero.
        EvalMetrics metrics = metrics(1, 1, 0, 0, 0.0, 0, 0.0, 0);

        ThresholdEvaluator.Result result =
                ThresholdEvaluator.evaluate(metrics, new EvalProperties.Thresholds(), true);

        assertTrue(result.passed(), result.messages().toString());
    }

    @Test
    void disabledThresholdsAlwaysPass() {
        EvalMetrics metrics = metrics(0, 1, 0, 0, 0.0, 0, 0.0, 1.0);

        ThresholdEvaluator.Result result =
                ThresholdEvaluator.evaluate(metrics, new EvalProperties.Thresholds(), false);

        assertFalse(result.enabled());
        assertTrue(result.passed());
    }

    private static EvalMetrics metrics(int questions, int succeeded, double car,
                                       int citationsJudged, double gcr, int exactJudged,
                                       double successIgnored, double contradictionRate) {
        int claimsWithCitations = citationsJudged > 0 ? 5 : 0;
        int keyPoints = gcr > 0 ? 2 : 0;
        double totalWeight = keyPoints == 0 ? 0.0 : 2.0;
        double coveredWeight = gcr * totalWeight;
        return new EvalMetrics(
                questions, succeeded, questions - succeeded, 0,
                1000, 1500, 100, 1000,
                5, citationsJudged,
                5, 5, 1.0,
                0, 0, 0, 0, 0.0,
                citationsJudged, (int) Math.round(car * citationsJudged), 0, 0, 0,
                claimsWithCitations, car, car,
                claimsWithCitations == 0 ? 0.0 : 1.0, 1.0,
                keyPoints, coveredWeight, totalWeight, gcr, keyPoints > 0 ? 1 : 0,
                exactJudged, 0, contradictionRate, 1.0,
                citationsJudged, 0, 0, 0, 0,
                0, java.util.Map.of(), questions);
    }
}
