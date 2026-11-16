package com.dra.eval.report;

import com.dra.eval.calibration.Calibration;
import com.dra.eval.support.EvalExitCode;

/**
 * Exit-code precedence for one run. Kept pure so the ordering is unit-testable:
 * invariant (invalid run) > infra > budget > judge calibration > thresholds > baseline.
 */
public final class RunGate {

    private RunGate() {
    }

    public record Inputs(
            boolean blockingInvariant,
            boolean infraFailure,
            boolean budgetExceeded,
            boolean judgeEnabled,
            Calibration.Report calibration,
            double kappaThreshold,
            boolean confidenceFailure,
            ThresholdEvaluator.Result thresholds,
            BaselineComparator.Diff baselineDiff) {
    }

    public static int decide(Inputs inputs) {
        if (inputs.blockingInvariant()) {
            return EvalExitCode.INVARIANT;
        }
        if (inputs.infraFailure()) {
            return EvalExitCode.INFRA;
        }
        if (inputs.budgetExceeded()) {
            return EvalExitCode.BUDGET;
        }
        if (inputs.judgeEnabled() && inputs.calibration() != null
                && inputs.calibration().labeled()
                && inputs.calibration().minKappa() < inputs.kappaThreshold()) {
            return EvalExitCode.THRESHOLD;
        }
        if (inputs.confidenceFailure()) {
            return EvalExitCode.THRESHOLD;
        }
        if (inputs.thresholds() != null && inputs.thresholds().enabled()
                && !inputs.thresholds().passed()) {
            return EvalExitCode.THRESHOLD;
        }
        if (inputs.baselineDiff() != null && inputs.baselineDiff().regressed()) {
            return EvalExitCode.THRESHOLD;
        }
        return EvalExitCode.OK;
    }
}
