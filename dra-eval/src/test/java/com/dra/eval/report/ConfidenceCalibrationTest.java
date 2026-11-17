package com.dra.eval.report;

import com.dra.eval.judge.ClaimSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceCalibrationTest {

    @Test
    void perfectCalibrationHasZeroError() {
        List<ClaimSample> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            samples.add(sample(1.0, true));
            samples.add(sample(0.0, false));
        }

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(samples, 10, 0.8);

        assertEquals(10, result.samples());
        assertEquals(0.0, result.ece(), 1e-9);
        assertEquals(0.0, result.brier(), 1e-9);
        assertEquals(1.0, result.highConfidenceAccuracy(), 1e-9);
        assertEquals(5, result.highConfidenceSamples());
    }

    @Test
    void overconfidenceProducesExpectedEceAndBrier() {
        List<ClaimSample> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            samples.add(sample(0.9, true));
            samples.add(sample(0.9, false));
        }

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(samples, 10, 0.8);

        assertEquals(0.4, result.ece(), 1e-9);
        assertEquals(0.41, result.brier(), 1e-9);
        assertEquals(0.5, result.highConfidenceAccuracy(), 1e-9);
        assertEquals(10, result.highConfidenceSamples());
        assertEquals(10, result.bins().stream().mapToInt(ConfidenceCalibration.Bin::count).sum());
    }

    @Test
    void confidenceOneFallsIntoTheLastBin() {
        List<ClaimSample> samples = List.of(sample(1.0, true), sample(1.0, true));

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(samples, 10, 0.8);

        ConfidenceCalibration.Bin last = result.bins().get(9);
        assertEquals(2, last.count());
        assertEquals(1.0, last.upper(), 1e-9);
        assertEquals(1.0, last.accuracy(), 1e-9);
    }

    @Test
    void highConfidenceThresholdIsInclusive() {
        List<ClaimSample> samples = List.of(sample(0.8, true), sample(0.79, false));

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(samples, 10, 0.8);

        assertEquals(1, result.highConfidenceSamples());
        assertEquals(1.0, result.highConfidenceAccuracy(), 1e-9);
    }

    @Test
    void emptySamplesAreReportedAsInsufficient() {
        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(List.of(), 10, 0.8);

        assertEquals(0, result.samples());
        assertFalse(result.sufficient(100));
        assertTrue(result.bins().isEmpty());
        assertTrue(result.components().isEmpty());
        assertNull(result.recommendation());
    }

    @Test
    void componentStatsSeparateCorrectFromIncorrect() {
        List<ClaimSample> samples = mixedComponentSamples();

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(samples, 10, 0.8);

        assertEquals(3, result.components().size());
        ConfidenceCalibration.ComponentStats judge = result.components().get(0);
        assertEquals("judgeConfidence", judge.name());
        assertEquals(40, judge.samples());
        assertEquals(0.95, judge.meanWhenCorrect(), 1e-9);
        assertEquals(0.2, judge.meanWhenIncorrect(), 1e-9);
    }

    @Test
    void recommendationNeedsEnoughComponentSamples() {
        List<ClaimSample> few = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            few.add(withComponents(0.9, i % 2 == 0, 0.9, 1.0, 1.0));
        }

        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(few, 10, 0.8);

        assertEquals(3, result.components().size());
        assertNull(result.recommendation(), "n<30 must not produce a weight suggestion");
    }

    @Test
    void recommendedWeightsSumToOneAndDoNotWorsenEce() {
        ConfidenceCalibration.Result result = ConfidenceCalibration.evaluate(
                mixedComponentSamples(), 10, 0.8);

        ConfidenceCalibration.Recommendation recommendation = result.recommendation();

        assertNotNull(recommendation);
        assertEquals(1.0, recommendation.judgeWeight() + recommendation.ruleWeight()
                + recommendation.diversityWeight(), 1e-9);
        assertTrue(recommendation.ece() <= result.ece() + 1e-9,
                "grid search cannot be worse than the stored score on the same samples");
    }

    private static List<ClaimSample> mixedComponentSamples() {
        List<ClaimSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(withComponents(0.975, true, 0.95, 1.0, 1.0));
            samples.add(withComponents(0.317, false, 0.2, 0.5, 1.0 / 3.0));
        }
        return samples;
    }

    private static ClaimSample withComponents(double confidence, boolean correct,
                                              double judge, double rule, double diversity) {
        return new ClaimSample("q1", "c-" + confidence + "-" + correct, confidence, correct,
                "J1", judge, rule, diversity);
    }

    private static ClaimSample sample(double confidence, boolean correct) {
        return new ClaimSample("q1", "c-" + confidence + "-" + correct, confidence, correct, "J1");
    }
}
