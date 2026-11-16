package com.dra.eval.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares a run against a frozen baseline. Regression rules come from M9-eval.md §6.5:
 * rates regress when they drop by 3pp or 5% relative; latency/cost tolerate +15%.
 * Runs whose dataset, depth or judge config differs are reported as not comparable and
 * never fail the build.
 */
public final class BaselineComparator {

    private BaselineComparator() {
    }

    public record Delta(String metric, double baseline, double current,
                        double deltaAbs, double deltaPct, boolean regression) {
    }

    public record Diff(boolean comparable, String reason, List<Delta> deltas,
                       List<Delta> regressions) {

        public Diff {
            deltas = deltas == null ? List.of() : List.copyOf(deltas);
            regressions = regressions == null ? List.of() : List.copyOf(regressions);
        }

        public static Diff notComparable(String reason) {
            return new Diff(false, reason, List.of(), List.of());
        }

        public boolean regressed() {
            return comparable && !regressions.isEmpty();
        }
    }

    public static Diff compare(Baseline baseline, EvalMetrics current,
                               Baseline.DatasetFingerprint currentFingerprint,
                               String currentDepth, boolean currentJudgeEnabled,
                               String currentJudgeModel, boolean includePerformance) {
        if (!Objects.equals(baseline.datasetSha256(), currentFingerprint.sha256())) {
            return Diff.notComparable("dataset sha256 changed");
        }
        if (!Objects.equals(nullToEmpty(baseline.depth()), nullToEmpty(currentDepth))) {
            return Diff.notComparable("depth changed");
        }
        if (baseline.judgeEnabled() != currentJudgeEnabled) {
            return Diff.notComparable("judge enabled state changed");
        }
        if (currentJudgeEnabled
                && !Objects.equals(nullToEmpty(baseline.judgeModel()), nullToEmpty(currentJudgeModel))) {
            return Diff.notComparable("judge model changed");
        }

        Baseline.Snapshot b = baseline.metrics();
        EvalMetrics m = current;
        double successRate = m.questions() == 0 ? 0.0 : (double) m.succeeded() / m.questions();

        List<Delta> deltas = new ArrayList<>();
        addRateHigher(deltas, "carStrict", b.carStrict(), m.carStrict());
        addRateHigher(deltas, "carWeighted", b.carWeighted(), m.carWeighted());
        addRateHigher(deltas, "hfr", b.hfr(), m.hfr());
        addRateHigher(deltas, "csr", b.csr(), m.csr());
        addRateHigher(deltas, "gcr", b.gcr(), m.gcr());
        addRateHigher(deltas, "claimPrecision", b.claimPrecision(), m.claimPrecision());
        addRateHigher(deltas, "anchorRate", b.anchorRate(), m.anchorRate());
        addRateHigher(deltas, "urlLiveRate", b.urlLiveRate(), m.urlLiveRate());
        addSuccessRate(deltas, b.successRate(), successRate);
        addRateLower(deltas, "contradictionRate", b.contradictionRate(), m.contradictionRate());
        if (includePerformance) {
            addAmountLower(deltas, "latencyP50Ms", b.latencyP50Ms(), m.latencyP50Ms());
            addAmountLower(deltas, "latencyP95Ms", b.latencyP95Ms(), m.latencyP95Ms());
            addAmountLower(deltas, "costMicros", b.costMicros(), m.costMicros());
            addAmountLower(deltas, "judgeCostMicros", b.judgeCostMicros(), m.judgeCostMicros());
        }

        List<Delta> regressions = deltas.stream().filter(Delta::regression).toList();
        return new Diff(true, null, deltas, regressions);
    }

    private static void addRateHigher(List<Delta> deltas, String metric, double baseline, double current) {
        if (skip(baseline, current)) {
            return;
        }
        double deltaAbs = current - baseline;
        double deltaPct = percent(baseline, deltaAbs);
        boolean regression = deltaAbs <= -0.03 || (baseline > 0 && deltaPct <= -0.05);
        deltas.add(new Delta(metric, baseline, current, deltaAbs, deltaPct, regression));
    }

    private static void addRateLower(List<Delta> deltas, String metric, double baseline, double current) {
        if (skip(baseline, current)) {
            return;
        }
        double deltaAbs = current - baseline;
        double deltaPct = percent(baseline, deltaAbs);
        boolean regression = deltaAbs >= 0.03 || (baseline > 0 && deltaPct >= 0.05);
        deltas.add(new Delta(metric, baseline, current, deltaAbs, deltaPct, regression));
    }

    private static void addSuccessRate(List<Delta> deltas, double baseline, double current) {
        if (skip(baseline, current)) {
            return;
        }
        double deltaAbs = current - baseline;
        deltas.add(new Delta("successRate", baseline, current, deltaAbs, percent(baseline, deltaAbs),
                deltaAbs <= -0.01));
    }

    private static void addAmountLower(List<Delta> deltas, String metric, long baseline, long current) {
        if (baseline == 0 && current == 0) {
            return;
        }
        double deltaAbs = current - baseline;
        double deltaPct = baseline == 0 ? 0.0 : deltaAbs / baseline;
        boolean regression = baseline > 0 && deltaPct >= 0.15;
        deltas.add(new Delta(metric, baseline, current, deltaAbs, deltaPct, regression));
    }

    private static boolean skip(double baseline, double current) {
        return baseline == 0.0 && current == 0.0;
    }

    private static double percent(double baseline, double deltaAbs) {
        return baseline == 0.0 ? 0.0 : deltaAbs / Math.abs(baseline);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String describe(Delta delta) {
        return String.format(Locale.ROOT, "%s: %.3f → %.3f (%+.1f%%)%s",
                delta.metric(), delta.baseline(), delta.current(),
                delta.deltaPct() * 100, delta.regression() ? " REGRESSION" : "");
    }
}
