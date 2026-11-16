package com.dra.eval.report;

import com.dra.eval.calibration.Calibration;
import com.dra.eval.config.EvalMode;
import com.dra.eval.dataset.DatasetLoader;
import com.dra.eval.judge.ClaimSample;
import com.dra.eval.judge.JudgeClient;
import com.dra.eval.judge.LabelItem;
import com.dra.eval.runner.QuestionResult;

import java.time.Instant;
import java.util.List;

/** Everything one run produced, passed to {@link ArtifactWriter} as a single value. */
public record RunReport(
        String runId,
        EvalMode mode,
        Instant startedAt,
        Instant finishedAt,
        DatasetLoader.LoadedDataset dataset,
        List<QuestionResult> results,
        EvalMetrics metrics,
        int exitCode,
        boolean urlCheckEnabled,
        boolean judgeEnabled,
        String depth,
        List<LabelItem> labelItems,
        String labelsPath,
        boolean templateWritten,
        Calibration.Report calibration,
        JudgeClient.Counters judgeCounters,
        ThresholdEvaluator.Result thresholds,
        BaselineComparator.Diff baselineDiff,
        String baselinePath,
        boolean baselineFrozen,
        ConfidenceCalibration.Result confidenceCalibration,
        List<ClaimSample> claimSamples,
        String rescoreFrom) {

    public RunReport {
        results = results == null ? List.of() : List.copyOf(results);
        labelItems = labelItems == null ? List.of() : List.copyOf(labelItems);
        claimSamples = claimSamples == null ? List.of() : List.copyOf(claimSamples);
        depth = depth == null ? "" : depth;
        baselinePath = baselinePath == null ? "" : baselinePath;
        rescoreFrom = rescoreFrom == null ? "" : rescoreFrom;
    }
}
