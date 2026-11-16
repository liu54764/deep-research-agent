package com.dra.eval.report;

import com.dra.eval.judge.ClaimSample;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Confidence calibration over rendered claims: ECE (expected calibration error) and Brier
 * score. Correctness comes from the independent judge, never from the system's own status.
 */
public final class ConfidenceCalibration {

    private ConfidenceCalibration() {
    }

    public record Bin(double lower, double upper, int count,
                      double avgConfidence, double accuracy) {
    }

    public record Result(
            int samples,
            double ece,
            double brier,
            double highConfidenceAccuracy,
            int highConfidenceSamples,
            List<Bin> bins) {

        public Result {
            bins = bins == null ? List.of() : List.copyOf(bins);
        }

        public static Result empty() {
            return new Result(0, 0.0, 0.0, 0.0, 0, List.of());
        }

        public boolean sufficient(int minSamples) {
            return samples >= minSamples;
        }

        public String describe() {
            return String.format(Locale.ROOT,
                    "n=%d ECE=%.3f Brier=%.3f high-conf acc=%.3f (n=%d)",
                    samples, ece, brier, highConfidenceAccuracy, highConfidenceSamples);
        }
    }

    public static Result evaluate(List<ClaimSample> samples, int binCount, double highThreshold) {
        if (samples == null || samples.isEmpty() || binCount <= 0) {
            return Result.empty();
        }
        int n = samples.size();
        double brier = 0.0;
        int[] counts = new int[binCount];
        double[] confidenceSum = new double[binCount];
        int[] correctCounts = new int[binCount];
        int highN = 0;
        int highCorrect = 0;

        for (ClaimSample sample : samples) {
            double confidence = clamp(sample.confidence());
            double outcome = sample.correct() ? 1.0 : 0.0;
            brier += (confidence - outcome) * (confidence - outcome);
            int bin = Math.min(binCount - 1, (int) (confidence * binCount));
            counts[bin]++;
            confidenceSum[bin] += confidence;
            if (sample.correct()) {
                correctCounts[bin]++;
            }
            if (confidence >= highThreshold) {
                highN++;
                if (sample.correct()) {
                    highCorrect++;
                }
            }
        }

        List<Bin> bins = new ArrayList<>(binCount);
        double ece = 0.0;
        for (int i = 0; i < binCount; i++) {
            double lower = (double) i / binCount;
            double upper = (double) (i + 1) / binCount;
            double avgConfidence = counts[i] == 0 ? 0.0 : confidenceSum[i] / counts[i];
            double accuracy = counts[i] == 0 ? 0.0 : (double) correctCounts[i] / counts[i];
            if (counts[i] > 0) {
                ece += ((double) counts[i] / n) * Math.abs(accuracy - avgConfidence);
            }
            bins.add(new Bin(lower, upper, counts[i], avgConfidence, accuracy));
        }

        return new Result(n, ece, brier / n,
                highN == 0 ? 0.0 : (double) highCorrect / highN, highN, bins);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
