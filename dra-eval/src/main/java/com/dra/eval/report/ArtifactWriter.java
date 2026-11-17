package com.dra.eval.report;

import com.dra.eval.calibration.Calibration;
import com.dra.eval.config.EvalMode;
import com.dra.eval.config.EvalProperties;
import com.dra.eval.invariant.InvariantChecker;
import com.dra.eval.judge.LabelItem;
import com.dra.eval.runner.QuestionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the run artifact set described in M9-eval.md §6.5. */
@Component
public class ArtifactWriter {

    private final ObjectMapper objectMapper;
    private final EvalProperties properties;

    public ArtifactWriter(ObjectMapper objectMapper, EvalProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Path write(RunReport report) {
        Path runDir = Path.of(properties.getOut(), report.runId());
        try {
            Files.createDirectories(runDir);
            writeJson(runDir.resolve("run.json"), manifest(report));
            writeJson(runDir.resolve("task-index.json"), taskIndex(report.results()));
            writeJsonLines(runDir.resolve("per-question.jsonl"), report.results());
            writeJson(runDir.resolve("invariants.json"), invariants(report.runId(), report.results()));
            writeJson(runDir.resolve("metrics.json"), report.metrics());
            if (report.thresholds() != null && report.thresholds().enabled()) {
                writeJson(runDir.resolve("thresholds.json"), report.thresholds());
            }
            if (report.baselineDiff() != null) {
                writeJson(runDir.resolve("baseline-diff.json"), report.baselineDiff());
            }
            if (report.confidenceCalibration() != null
                    && report.confidenceCalibration().samples() > 0) {
                writeJson(runDir.resolve("confidence-calibration.json"),
                        report.confidenceCalibration());
                writeJsonLines(runDir.resolve("calibration-samples.jsonl"),
                        report.claimSamples());
            }
            if (report.calibration() != null) {
                writeJson(runDir.resolve("calibration.json"), report.calibration());
            }
            Files.writeString(runDir.resolve("summary.md"), summary(report), StandardCharsets.UTF_8);
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write run artifacts to " + runDir, e);
        }
    }

    private Map<String, Object> manifest(RunReport report) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", report.runId());
        manifest.put("mode", report.mode().value());
        manifest.put("startedAt", report.startedAt().toString());
        manifest.put("finishedAt", report.finishedAt().toString());
        manifest.put("exitCode", report.exitCode());
        if (!report.rescoreFrom().isBlank()) {
            manifest.put("rescoreFrom", report.rescoreFrom());
        }
        manifest.put("dataset", Map.of(
                "path", report.dataset().path(),
                "sha256", report.dataset().sha256(),
                "versions", report.dataset().datasetVersions(),
                "total", report.dataset().total(),
                "selected", report.dataset().questions().size(),
                "verified", report.dataset().verified()));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiBaseUrl", properties.getApiBaseUrl());
        config.put("depth", report.depth());
        config.put("parallel", properties.getParallel());
        config.put("maxCostCny", properties.getMaxCostCny());
        config.put("urlCheck", report.urlCheckEnabled());
        config.put("thresholdsEnabled", report.thresholds() != null && report.thresholds().enabled());
        manifest.put("config", config);

        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("enabled", report.judgeEnabled());
        judge.put("model", properties.getJudge().getModel());
        judge.put("promptVersions", List.of("j1.v1", "j2.v1", "j3.v1"));
        judge.put("cacheDir", properties.getCacheDir());
        judge.put("calls", report.judgeCounters().calls());
        judge.put("cacheHits", report.judgeCounters().cacheHits());
        judge.put("invalidOutputs", report.judgeCounters().invalidOutputs());
        judge.put("failures", report.judgeCounters().failures());
        judge.put("promptTokens", report.judgeCounters().promptTokens());
        judge.put("completionTokens", report.judgeCounters().completionTokens());
        judge.put("costMicros", report.judgeCounters().costMicros());
        manifest.put("judge", judge);

        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("path", report.labelsPath());
        labels.put("sampled", report.labelItems().size());
        labels.put("templateWritten", report.templateWritten());
        if (report.calibration() != null) {
            labels.put("calibrationStatus", report.calibration().status());
            labels.put("labeledTotal", report.calibration().labeledTotal());
            labels.put("minKappa", report.calibration().minKappa());
        }
        manifest.put("labels", labels);

        Map<String, Object> thresholds = new LinkedHashMap<>();
        if (report.thresholds() != null) {
            thresholds.put("enabled", report.thresholds().enabled());
            thresholds.put("passed", report.thresholds().passed());
            thresholds.put("failures", report.thresholds().messages());
        }
        manifest.put("thresholds", thresholds);

        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("path", report.baselinePath());
        baseline.put("frozen", report.baselineFrozen());
        if (report.baselineDiff() != null) {
            baseline.put("comparable", report.baselineDiff().comparable());
            baseline.put("reason", report.baselineDiff().reason());
            baseline.put("regressions", report.baselineDiff().regressions().stream()
                    .map(BaselineComparator::describe).toList());
        }
        manifest.put("baseline", baseline);

        Map<String, Object> confidence = new LinkedHashMap<>();
        ConfidenceCalibration.Result calibrationMetrics = report.confidenceCalibration();
        if (calibrationMetrics != null) {
            confidence.put("samples", calibrationMetrics.samples());
            confidence.put("ece", calibrationMetrics.ece());
            confidence.put("brier", calibrationMetrics.brier());
            confidence.put("highConfidenceAccuracy", calibrationMetrics.highConfidenceAccuracy());
            confidence.put("highConfidenceSamples", calibrationMetrics.highConfidenceSamples());
            confidence.put("sufficient", calibrationMetrics.sufficient(
                    properties.getConfidenceMinSamples()));
            confidence.put("minSamples", properties.getConfidenceMinSamples());
            confidence.put("maxEce", properties.getConfidenceMaxEce());
        }
        manifest.put("confidenceCalibration", confidence);
        manifest.put("questions", report.metrics().questions());
        return manifest;
    }

    private List<Map<String, Object>> taskIndex(List<QuestionResult> results) {
        List<Map<String, Object>> index = new ArrayList<>();
        for (QuestionResult result : results) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("questionId", result.questionId());
            entry.put("taskId", result.taskId());
            entry.put("status", result.status());
            index.add(entry);
        }
        return index;
    }

    private Map<String, Object> invariants(String runId, List<QuestionResult> results) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (QuestionResult result : results) {
            for (InvariantChecker.Violation violation : result.violations()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("questionId", result.questionId());
                entry.put("id", violation.id());
                entry.put("detail", violation.detail());
                violations.add(entry);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("passed", violations.isEmpty());
        body.put("violations", violations);
        return body;
    }

    private String summary(RunReport report) {
        EvalMetrics metrics = report.metrics();
        StringBuilder md = new StringBuilder();
        md.append("# Eval run ").append(report.runId()).append("\n\n");
        md.append("- mode: `").append(report.mode().value()).append("`\n");
        if (!report.rescoreFrom().isBlank()) {
            md.append("- rescore from: `").append(report.rescoreFrom()).append("`\n");
        }
        md.append("- dataset: `").append(report.dataset().path()).append("` (sha256 ")
                .append(shortSha(report.dataset().sha256())).append(")\n");
        md.append("- questions: ").append(metrics.questions())
                .append(" / dataset ").append(report.dataset().total())
                .append(" (gold verified: ").append(report.dataset().verified()).append(")\n");
        md.append("- judge: ").append(report.judgeEnabled()
                        ? "`" + properties.getJudge().getModel() + "`" : "disabled")
                .append("\n");
        md.append("- url check: ").append(report.urlCheckEnabled() ? "on" : "off").append("\n");
        md.append("- exit code: ").append(report.exitCode()).append("\n\n");

        md.append("## Headline (deterministic)\n\n");
        md.append("| metric | value |\n|---|---|\n");
        md.append("| succeeded / total | ").append(metrics.succeeded()).append(" / ")
                .append(metrics.questions()).append(" |\n");
        md.append("| latency P50 / P95 | ").append(seconds(metrics.latencyP50Ms())).append(" / ")
                .append(seconds(metrics.latencyP95Ms())).append(" |\n");
        md.append("| cost (generation) | ").append(cny(metrics.costMicros())).append(" |\n");
        md.append("| tokens | ").append(metrics.tokens()).append(" |\n");
        md.append("| claims / citations | ").append(metrics.claims()).append(" / ")
                .append(metrics.citations()).append(" |\n");
        md.append("| anchor hit rate (ACR) | ").append(rate(metrics.anchorRate()))
                .append(" (").append(metrics.anchoredCitations()).append("/")
                .append(metrics.fullTextCitations()).append(" full-text citations) |\n");
        if (metrics.urlSampled() > 0) {
            md.append("| url live rate | ").append(rate(metrics.urlLiveRate()))
                    .append(" (live ").append(metrics.urlLive()).append(", dead ")
                    .append(metrics.urlDead()).append(", unverifiable ")
                    .append(metrics.urlUnverifiable()).append(") |\n");
        }
        md.append("| invariant violations | ").append(metrics.invariantViolations())
                .append(metrics.violationsByInvariant().isEmpty() ? "" : " " + metrics.violationsByInvariant())
                .append(" |\n\n");

        if (metrics.judgeRuns()) {
            md.append("## Judge metrics (independent model)\n\n");
            md.append("| metric | value |\n|---|---|\n");
            md.append("| CAR strict / weighted | ").append(rate(metrics.carStrict()))
                    .append(" / ").append(rate(metrics.carWeighted()))
                    .append(" (").append(metrics.citationsJudged()).append(" citations) |\n");
            md.append("| verdicts S/P/R/U | ").append(metrics.citationSupport()).append(" / ")
                    .append(metrics.citationPartial()).append(" / ")
                    .append(metrics.citationRefute()).append(" / ")
                    .append(metrics.citationUnrelated()).append(" |\n");
            md.append("| HFR (claims w/o invalid citation) | ").append(rate(metrics.hfr())).append(" |\n");
            md.append("| CSR (claims with ≥1 SUPPORT) | ").append(rate(metrics.csr())).append(" |\n");
            md.append("| GCR (weighted gold coverage) | ").append(rate(metrics.gcr()))
                    .append(" (").append(String.format(Locale.ROOT, "%.2f/%.2f",
                            metrics.coveredWeight(), metrics.totalWeight()))
                    .append(", ").append(metrics.gcrQuestions()).append("/")
                    .append(metrics.succeeded()).append(" questions judged) |\n");
            md.append("| contradiction rate (exact) | ").append(rate(metrics.contradictionRate()))
                    .append(" (").append(metrics.contradicted()).append("/")
                    .append(metrics.exactClaimsJudged()).append(") |\n");
            md.append("| claim precision (exact) | ").append(rate(metrics.claimPrecision())).append(" |\n");
            md.append("| judge calls / cache hits / invalid | ").append(metrics.judgeCalls())
                    .append(" / ").append(metrics.judgeCacheHits()).append(" / ")
                    .append(metrics.judgeInvalidOutputs()).append(" |\n");
            md.append("| judge cost / tokens | ").append(cny(metrics.judgeCostMicros()))
                    .append(" / ").append(metrics.judgeTokens()).append(" |\n\n");
        }

        ConfidenceCalibration.Result confidence = report.confidenceCalibration();
        if (confidence != null && confidence.samples() > 0) {
            md.append("## Confidence calibration\n\n");
            md.append("- ").append(confidence.describe()).append('\n');
            boolean sufficient = confidence.sufficient(properties.getConfidenceMinSamples());
            md.append("- samples: ").append(confidence.samples())
                    .append(" (min ").append(properties.getConfidenceMinSamples()).append(")")
                    .append(sufficient ? " → ECE gated at "
                            + String.format(Locale.ROOT, "%.2f", properties.getConfidenceMaxEce())
                            : " → **insufficient, ECE not gated**")
                    .append('\n');
            md.append("\n| bin | n | avg confidence | accuracy |\n|---|---|---|---|\n");
            for (ConfidenceCalibration.Bin bin : confidence.bins()) {
                if (bin.count() == 0) {
                    continue;
                }
                md.append("| ").append(String.format(Locale.ROOT, "%.1f–%.1f",
                                bin.lower(), bin.upper()))
                        .append(" | ").append(bin.count())
                        .append(" | ").append(rate(bin.avgConfidence()))
                        .append(" | ").append(rate(bin.accuracy()))
                        .append(" |\n");
            }
            md.append('\n');

            if (!confidence.components().isEmpty()) {
                md.append("### Score components\n\n");
                md.append("| component | n | mean | mean (correct) | mean (incorrect) | ECE |\n")
                        .append("|---|---|---|---|---|---|\n");
                for (ConfidenceCalibration.ComponentStats component : confidence.components()) {
                    md.append("| ").append(component.name())
                            .append(" | ").append(component.samples())
                            .append(" | ").append(rate(component.mean()))
                            .append(" | ").append(rate(component.meanWhenCorrect()))
                            .append(" | ").append(rate(component.meanWhenIncorrect()))
                            .append(" | ").append(rate(component.ece()))
                            .append(" |\n");
                }
                md.append('\n');
            }
            if (confidence.recommendation() != null) {
                md.append("- weight suggestion (in-sample, rule:diversity fixed 3:2): ")
                        .append(confidence.recommendation().describe())
                        .append('\n');
                md.append("  - confirm on a fresh run before changing "
                        + "`dra.verify.confidence.*`\n\n");
            }
        }

        ThresholdEvaluator.Result thresholds = report.thresholds();
        if (thresholds != null && thresholds.enabled()) {
            md.append("## Thresholds\n\n");
            if (thresholds.passed()) {
                md.append("- all M0 acceptance thresholds met\n\n");
            } else {
                md.append("- **FAILED**\n");
                for (String message : thresholds.messages()) {
                    md.append("  - ").append(message).append('\n');
                }
                md.append('\n');
            }
        }

        BaselineComparator.Diff diff = report.baselineDiff();
        if (diff != null) {
            md.append("## Baseline diff\n\n");
            md.append("- baseline: `").append(report.baselinePath()).append("`\n");
            if (!diff.comparable()) {
                md.append("- **not comparable**: ").append(diff.reason()).append('\n');
            } else if (diff.regressed()) {
                md.append("- **REGRESSIONS**\n");
                for (BaselineComparator.Delta delta : diff.regressions()) {
                    md.append("  - ").append(BaselineComparator.describe(delta)).append('\n');
                }
            } else {
                md.append("- no regression against baseline\n");
            }
            if (!diff.deltas().isEmpty()) {
                md.append("\n| metric | baseline | current | delta |\n|---|---|---|---|\n");
                for (BaselineComparator.Delta delta : diff.deltas()) {
                    md.append("| ").append(delta.metric())
                            .append(" | ").append(number(delta.baseline()))
                            .append(" | ").append(number(delta.current()))
                            .append(" | ").append(String.format(Locale.ROOT, "%+.1f%%",
                                    delta.deltaPct() * 100))
                            .append(delta.regression() ? " ⚠" : "")
                            .append(" |\n");
                }
            }
            md.append('\n');
        } else if (report.baselineFrozen()) {
            md.append("## Baseline\n\n- frozen to `").append(report.baselinePath())
                    .append("`\n\n");
        }

        md.append("## Calibration\n\n");
        if (report.calibration() == null) {
            md.append("- status: `UNLABELED` (no label file for this run)\n");
        } else {
            md.append("- status: `").append(report.calibration().status()).append("`, labeled ")
                    .append(report.calibration().labeledTotal()).append(" items\n");
            for (Map.Entry<String, Calibration.KindResult> entry : report.calibration().byKind().entrySet()) {
                Calibration.KindResult kind = entry.getValue();
                md.append("- ").append(entry.getKey()).append(": κ=")
                        .append(String.format(Locale.ROOT, "%.3f", kind.kappa()))
                        .append(" (n=").append(kind.labeled()).append(", agreement ")
                        .append(kind.agreement()).append(")\n");
            }
            if (report.calibration().labeled()) {
                md.append("- min κ ").append(String.format(Locale.ROOT, "%.3f",
                                report.calibration().minKappa()))
                        .append(" vs threshold ")
                        .append(String.format(Locale.ROOT, "%.2f", properties.getKappaThreshold()))
                        .append(report.calibration().minKappa() >= properties.getKappaThreshold()
                                ? " → judge trusted" : " → **judge NOT trusted**").append("\n");
            }
        }
        md.append("- labels file: `").append(report.labelsPath()).append("`\n");
        if (report.templateWritten()) {
            md.append("- template written this run: fill `humanVerdict` (null → verdict), then "
                    + "`--mode=calibrate --labels=").append(report.labelsPath()).append("`\n");
        }
        md.append('\n');

        md.append("## Per question\n\n");
        md.append("| id | status | latency | cost | claims | citations | anchor | CAR | GCR | violations |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (QuestionResult result : report.results()) {
            md.append("| ").append(result.questionId())
                    .append(" | ").append(result.status())
                    .append(" | ").append(seconds(result.latencyMs()))
                    .append(" | ").append(cny(result.costMicros()))
                    .append(" | ").append(result.claims())
                    .append(" | ").append(result.citations())
                    .append(" | ").append(rate(result.rule().anchorRate()))
                    .append(" | ").append(result.judge().citationsJudged() == 0
                            ? "-" : rate(result.judge().carStrict()))
                    .append(" | ").append(result.judge().totalWeight() == 0
                            ? "-" : rate(result.judge().gcr()))
                    .append(" | ").append(result.violations().isEmpty()
                            ? "-" : String.valueOf(result.violations().size()))
                    .append(" |\n");
        }
        md.append('\n');

        if (!metrics.violationsByInvariant().isEmpty()) {
            md.append("## Invariant violations\n\n");
            for (QuestionResult result : report.results()) {
                for (InvariantChecker.Violation violation : result.violations()) {
                    md.append("- `").append(violation.id()).append("` ").append(result.questionId())
                            .append(": ").append(violation.detail()).append("\n");
                }
            }
            md.append('\n');
        }

        md.append("## Caveats\n\n");
        if (report.mode() == EvalMode.SELF_TEST) {
            md.append("- **self-test**: mock LLM + mock search + stub fetch. These numbers validate "
                    + "the harness (plumbing, invariants, rule checks), **not** research quality.\n");
            md.append("- self-test tolerates `I3` (stub claims carry no citations) and `I8` "
                    + "(stub report meta omits stopReason); both are recorded above and become "
                    + "blocking in `real` mode.\n");
        }
        if (report.mode() == EvalMode.RESCORE) {
            md.append("- **rescore**: tasks were not re-run; reports were re-fetched and re-scored. "
                    + "Judge requests hit the content cache when prompts are unchanged.\n");
        }
        if (!report.judgeEnabled()) {
            md.append("- judge is disabled: CAR/HFR/GCR/ECE are absent. Enable it with "
                    + "`--judge-enabled=true` plus a judge key.\n");
        } else if (report.calibration() == null || !report.calibration().labeled()) {
            md.append("- judge output is **uncalibrated**: fill the label template and run "
                    + "`--mode=calibrate` before quoting these numbers.\n");
        }
        ConfidenceCalibration.Result confidenceResult = report.confidenceCalibration();
        if (confidenceResult != null && confidenceResult.samples() > 0
                && !confidenceResult.sufficient(properties.getConfidenceMinSamples())) {
            md.append("- confidence calibration has only ").append(confidenceResult.samples())
                    .append(" claim samples (min ").append(properties.getConfidenceMinSamples())
                    .append("); ECE is reported but not gated.\n");
        }
        long succeededResults = report.results().stream().filter(QuestionResult::succeeded).count();
        long gcrJudged = report.results().stream()
                .filter(r -> r.judge().totalWeight() > 0).count();
        if (report.judgeEnabled() && gcrJudged < succeededResults) {
            md.append("- GCR covers only ").append(gcrJudged).append(" of ").append(succeededResults)
                    .append(" succeeded questions: the others produced no parseable J2 verdict, so "
                            + "the aggregate is computed over the judged subset only.\n");
        }
        long verifiedResults = report.results().stream().filter(QuestionResult::goldVerified).count();
        md.append("- questions with verified gold in this run: ").append(verifiedResults)
                .append(" / ").append(report.results().size())
                .append("; unverified gold may only feed diagnostics.\n");
        return md.toString();
    }

    private void writeJson(Path path, Object value) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private void writeJsonLines(Path path, List<?> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Object value : values) {
            sb.append(objectMapper.writeValueAsString(value)).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 12 ? String.valueOf(sha) : sha.substring(0, 12);
    }

    private static String seconds(long millis) {
        return millis <= 0 ? "-" : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String cny(long micros) {
        return micros <= 0 ? "-" : String.format(Locale.ROOT, "¥%.4f", micros / 1_000_000.0);
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String number(double value) {
        return Math.abs(value) >= 100
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.3f", value);
    }
}
