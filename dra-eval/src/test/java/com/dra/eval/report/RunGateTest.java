package com.dra.eval.report;

import com.dra.eval.calibration.Calibration;
import com.dra.eval.support.EvalExitCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunGateTest {

    @Test
    void invariantViolationWinsOverEverything() {
        assertEquals(EvalExitCode.INVARIANT, RunGate.decide(inputs(true, true, true,
                failedThresholds(), regressedBaseline(), lowKappa())));
    }

    @Test
    void infraBeatsBudgetAndThresholds() {
        assertEquals(EvalExitCode.INFRA, RunGate.decide(inputs(false, true, true,
                failedThresholds(), regressedBaseline(), lowKappa())));
    }

    @Test
    void budgetBeatsThresholds() {
        assertEquals(EvalExitCode.BUDGET, RunGate.decide(inputs(false, false, true,
                failedThresholds(), regressedBaseline(), lowKappa())));
    }

    @Test
    void lowKappaFailsWhenJudgeEnabled() {
        assertEquals(EvalExitCode.THRESHOLD, RunGate.decide(inputs(false, false, false,
                passingThresholds(), null, lowKappa())));
    }

    @Test
    void lowKappaIsIgnoredWhenJudgeDisabled() {
        RunGate.Inputs inputs = new RunGate.Inputs(false, false, false, false,
                lowKappa(), 0.7, false, passingThresholds(), null);

        assertEquals(EvalExitCode.OK, RunGate.decide(inputs));
    }

    @Test
    void thresholdFailureReturnsOne() {
        assertEquals(EvalExitCode.THRESHOLD, RunGate.decide(inputs(false, false, false,
                failedThresholds(), null, null)));
    }

    @Test
    void baselineRegressionReturnsOne() {
        assertEquals(EvalExitCode.THRESHOLD, RunGate.decide(inputs(false, false, false,
                passingThresholds(), regressedBaseline(), null)));
    }

    @Test
    void cleanRunReturnsZero() {
        assertEquals(EvalExitCode.OK, RunGate.decide(inputs(false, false, false,
                passingThresholds(), noRegression(), null)));
    }

    @Test
    void confidenceCalibrationFailureReturnsOne() {
        RunGate.Inputs inputs = new RunGate.Inputs(false, false, false, true, null, 0.7,
                true, passingThresholds(), null);

        assertEquals(EvalExitCode.THRESHOLD, RunGate.decide(inputs));
    }

    private static RunGate.Inputs inputs(boolean invariant, boolean infra, boolean budget,
                                         ThresholdEvaluator.Result thresholds,
                                         BaselineComparator.Diff diff,
                                         Calibration.Report calibration) {
        return new RunGate.Inputs(invariant, infra, budget, true, calibration, 0.7,
                false, thresholds, diff);
    }

    private static ThresholdEvaluator.Result failedThresholds() {
        return new ThresholdEvaluator.Result(true, false,
                List.of(new ThresholdEvaluator.Failure("carStrict", 0.5, ">= 0.85")));
    }

    private static ThresholdEvaluator.Result passingThresholds() {
        return new ThresholdEvaluator.Result(true, true, List.of());
    }

    private static Calibration.Report lowKappa() {
        return new Calibration.Report("LABELED", 16, 0.4, Map.of());
    }

    private static BaselineComparator.Diff regressedBaseline() {
        return new BaselineComparator.Diff(true, null, List.of(),
                List.of(new BaselineComparator.Delta("carStrict", 0.9, 0.6, -0.3, -0.33, true)));
    }

    private static BaselineComparator.Diff noRegression() {
        return new BaselineComparator.Diff(true, null, List.of(), List.of());
    }
}
