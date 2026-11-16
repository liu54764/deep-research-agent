package com.dra.eval.report;

import com.dra.eval.config.EvalProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies the M0 acceptance thresholds. A metric is only gated when it actually exists for
 * this run: no judge means CAR/HFR/GCR are absent, not zero.
 */
public final class ThresholdEvaluator {

    private ThresholdEvaluator() {
    }

    public record Failure(String metric, double actual, String rule) {
        public String describe() {
            return String.format(Locale.ROOT, "%s=%.3f (require %s)", metric, actual, rule);
        }
    }

    public record Result(boolean enabled, boolean passed, List<Failure> failures) {

        public Result {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public static Result disabled() {
            return new Result(false, true, List.of());
        }

        public List<String> messages() {
            return failures.stream().map(Failure::describe).toList();
        }
    }

    public static Result evaluate(EvalMetrics metrics, EvalProperties.Thresholds spec, boolean enabled) {
        return evaluate(metrics, spec, enabled, null);
    }

    public static Result evaluate(EvalMetrics metrics, EvalProperties.Thresholds spec,
                                  boolean enabled, String depth) {
        if (!enabled) {
            return Result.disabled();
        }
        double minGcr = spec.minGcrFor(depth);
        List<Failure> failures = new ArrayList<>();

        if (metrics.questions() > 0) {
            double successRate = (double) metrics.succeeded() / metrics.questions();
            if (successRate < spec.getMinSuccessRate()) {
                failures.add(new Failure("successRate", successRate,
                        ">= " + fmt(spec.getMinSuccessRate())));
            }
        }
        if (metrics.citationsJudged() > 0 && metrics.carStrict() < spec.getMinCar()) {
            failures.add(new Failure("carStrict", metrics.carStrict(),
                    ">= " + fmt(spec.getMinCar())));
        }
        if (metrics.claimsWithCitations() > 0 && metrics.hfr() < spec.getMinHfr()) {
            failures.add(new Failure("hfr", metrics.hfr(),
                    ">= " + fmt(spec.getMinHfr())));
        }
        if (metrics.totalWeight() > 0 && metrics.gcr() < minGcr) {
            failures.add(new Failure("gcr", metrics.gcr(),
                    ">= " + fmt(minGcr) + (depth == null ? "" : " (" + depth + ")")));
        }
        if (metrics.exactClaimsJudged() > 0
                && metrics.contradictionRate() > spec.getMaxContradictionRate()) {
            failures.add(new Failure("contradictionRate", metrics.contradictionRate(),
                    "<= " + fmt(spec.getMaxContradictionRate())));
        }
        return new Result(true, failures.isEmpty(), failures);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
