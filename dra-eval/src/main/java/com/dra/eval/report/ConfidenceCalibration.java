package com.dra.eval.report;

import com.dra.eval.judge.ClaimSample;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Confidence calibration over rendered claims: ECE (expected calibration error) and Brier
 * score. Correctness comes from the independent judge, never from the system's own status.
 *
 * <p>Runs written after V3 also carry the three score components (judge confidence, rule ratio,
 * diversity). They turn "the score is under-confident" into a measurement per component, plus an
 * in-sample weight suggestion. The suggestion is a grid search over the judge weight with the
 * rule:diversity split held at the historical 3:2, so it must be confirmed on a fresh run before
 * it becomes a default.
 */
public final class ConfidenceCalibration {

    /** Below this many component-bearing samples a weight suggestion is noise. */
    static final int MIN_RECOMMENDATION_SAMPLES = 30;

    private static final double RULE_SHARE_OF_FIXED_WEIGHT = 0.6;
    private static final double WEIGHT_STEP = 0.05;

    private ConfidenceCalibration() {
    }

    public record Bin(double lower, double upper, int count,
                      double avgConfidence, double accuracy) {
    }

    /** How one score component distributes across correct vs incorrect claims. */
    public record ComponentStats(
            String name,
            int samples,
            double mean,
            double meanWhenCorrect,
            double meanWhenIncorrect,
            double ece,
            double brier) {

        public String describe() {
            return String.format(Locale.ROOT,
                    "%s: mean=%.3f correct=%.3f incorrect=%.3f ECE=%.3f",
                    name, mean, meanWhenCorrect, meanWhenIncorrect, ece);
        }
    }

    /** In-sample linear-weight suggestion; weights sum to 1. */
    public record Recommendation(
            double judgeWeight,
            double ruleWeight,
            double diversityWeight,
            double ece,
            double brier,
            int samples) {

        public String describe() {
            return String.format(Locale.ROOT,
                    "judge=%.2f rule=%.2f diversity=%.2f ECE=%.3f Brier=%.3f (n=%d, in-sample)",
                    judgeWeight, ruleWeight, diversityWeight, ece, brier, samples);
        }
    }

    public record Result(
            int samples,
            double ece,
            double brier,
            double highConfidenceAccuracy,
            int highConfidenceSamples,
            List<Bin> bins,
            List<ComponentStats> components,
            Recommendation recommendation) {

        public Result {
            bins = bins == null ? List.of() : List.copyOf(bins);
            components = components == null ? List.of() : List.copyOf(components);
        }

        public static Result empty() {
            return new Result(0, 0.0, 0.0, 0.0, 0, List.of(), List.of(), null);
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
                highN == 0 ? 0.0 : (double) highCorrect / highN, highN, bins,
                componentStats(samples, binCount),
                recommend(samples, binCount));
    }

    // ---------------------------------------------------------------- components

    private static List<ComponentStats> componentStats(List<ClaimSample> samples, int binCount) {
        List<ClaimSample> usable = samples.stream().filter(ClaimSample::hasComponents).toList();
        if (usable.isEmpty()) {
            return List.of();
        }
        return List.of(
                stats("judgeConfidence", usable, ClaimSample::judgeConfidence, binCount),
                stats("ruleRatio", usable, ClaimSample::ruleRatio, binCount),
                stats("diversity", usable, ClaimSample::diversityScore, binCount));
    }

    private static ComponentStats stats(String name, List<ClaimSample> samples,
                                        ToDoubleFunction<ClaimSample> component, int binCount) {
        int n = samples.size();
        int correctCount = 0;
        int incorrectCount = 0;
        double sum = 0.0;
        double correctSum = 0.0;
        double incorrectSum = 0.0;
        double brier = 0.0;
        int[] counts = new int[binCount];
        double[] valueSum = new double[binCount];
        int[] binCorrect = new int[binCount];

        for (ClaimSample sample : samples) {
            double value = clamp(component.applyAsDouble(sample));
            double outcome = sample.correct() ? 1.0 : 0.0;
            sum += value;
            brier += (value - outcome) * (value - outcome);
            if (sample.correct()) {
                correctCount++;
                correctSum += value;
            } else {
                incorrectCount++;
                incorrectSum += value;
            }
            int bin = Math.min(binCount - 1, (int) (value * binCount));
            counts[bin]++;
            valueSum[bin] += value;
            if (sample.correct()) {
                binCorrect[bin]++;
            }
        }

        double ece = 0.0;
        for (int i = 0; i < binCount; i++) {
            if (counts[i] == 0) {
                continue;
            }
            double avgValue = valueSum[i] / counts[i];
            double accuracy = (double) binCorrect[i] / counts[i];
            ece += ((double) counts[i] / n) * Math.abs(accuracy - avgValue);
        }

        return new ComponentStats(name, n, sum / n,
                correctCount == 0 ? 0.0 : correctSum / correctCount,
                incorrectCount == 0 ? 0.0 : incorrectSum / incorrectCount,
                ece, brier / n);
    }

    // ---------------------------------------------------------------- recommendation

    private static Recommendation recommend(List<ClaimSample> samples, int binCount) {
        List<ClaimSample> usable = samples.stream().filter(ClaimSample::hasComponents).toList();
        if (usable.size() < MIN_RECOMMENDATION_SAMPLES) {
            return null;
        }
        Recommendation best = null;
        for (int step = 0; step * WEIGHT_STEP <= 1.0 + 1e-9; step++) {
            double judgeWeight = round(step * WEIGHT_STEP);
            double fixed = 1.0 - judgeWeight;
            double ruleWeight = round(fixed * RULE_SHARE_OF_FIXED_WEIGHT);
            double diversityWeight = round(fixed - ruleWeight);
            Recommendation candidate = new Recommendation(judgeWeight, ruleWeight,
                    diversityWeight,
                    weightedEce(usable, judgeWeight, ruleWeight, diversityWeight, binCount),
                    weightedBrier(usable, judgeWeight, ruleWeight, diversityWeight),
                    usable.size());
            if (best == null || better(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean better(Recommendation candidate, Recommendation best) {
        if (candidate.ece() < best.ece() - 1e-12) {
            return true;
        }
        return Math.abs(candidate.ece() - best.ece()) <= 1e-12 && candidate.brier() < best.brier();
    }

    private static double weightedEce(List<ClaimSample> samples, double judgeWeight,
                                      double ruleWeight, double diversityWeight, int binCount) {
        int n = samples.size();
        int[] counts = new int[binCount];
        double[] valueSum = new double[binCount];
        int[] correctCounts = new int[binCount];
        for (ClaimSample sample : samples) {
            double value = weightedConfidence(sample, judgeWeight, ruleWeight, diversityWeight);
            int bin = Math.min(binCount - 1, (int) (value * binCount));
            counts[bin]++;
            valueSum[bin] += value;
            if (sample.correct()) {
                correctCounts[bin]++;
            }
        }
        double ece = 0.0;
        for (int i = 0; i < binCount; i++) {
            if (counts[i] == 0) {
                continue;
            }
            double avgValue = valueSum[i] / counts[i];
            double accuracy = (double) correctCounts[i] / counts[i];
            ece += ((double) counts[i] / n) * Math.abs(accuracy - avgValue);
        }
        return ece;
    }

    private static double weightedBrier(List<ClaimSample> samples, double judgeWeight,
                                        double ruleWeight, double diversityWeight) {
        double brier = 0.0;
        for (ClaimSample sample : samples) {
            double value = weightedConfidence(sample, judgeWeight, ruleWeight, diversityWeight);
            double outcome = sample.correct() ? 1.0 : 0.0;
            brier += (value - outcome) * (value - outcome);
        }
        return brier / samples.size();
    }

    private static double weightedConfidence(ClaimSample sample, double judgeWeight,
                                             double ruleWeight, double diversityWeight) {
        return clamp(judgeWeight * sample.judgeConfidence()
                + ruleWeight * sample.ruleRatio()
                + diversityWeight * sample.diversityScore());
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
