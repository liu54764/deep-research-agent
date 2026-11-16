package com.dra.eval;

import com.dra.eval.calibration.Calibration;
import com.dra.eval.collect.DbSnapshot;
import com.dra.eval.collect.DetailReader;
import com.dra.eval.config.EvalMode;
import com.dra.eval.config.EvalProperties;
import com.dra.eval.dataset.DatasetLoader;
import com.dra.eval.dataset.EvalQuestion;
import com.dra.eval.invariant.InvariantChecker;
import com.dra.eval.judge.ClaimSample;
import com.dra.eval.judge.JudgeClient;
import com.dra.eval.judge.JudgeResult;
import com.dra.eval.judge.JudgeScorer;
import com.dra.eval.judge.LabelItem;
import com.dra.eval.judge.LabelSampler;
import com.dra.eval.report.ArtifactWriter;
import com.dra.eval.report.Baseline;
import com.dra.eval.report.BaselineComparator;
import com.dra.eval.report.ConfidenceCalibration;
import com.dra.eval.report.EvalMetrics;
import com.dra.eval.report.RunReport;
import com.dra.eval.report.RunGate;
import com.dra.eval.report.ThresholdEvaluator;
import com.dra.eval.runner.QuestionResult;
import com.dra.eval.runner.TaskRunner;
import com.dra.eval.score.rule.RuleScorer;
import com.dra.eval.support.EvalExitCode;
import com.dra.eval.support.EvalInfraException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates one eval run: dataset -> submit/poll (or reuse) -> collect -> invariants ->
 * rule scores -> judge scores -> thresholds -> baseline diff -> artifacts.
 */
@Component
public class EvalRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    /** Invariants a stub-mode run cannot satisfy; recorded but non-blocking (see decide()). */
    private static final java.util.Set<String> SELF_TEST_TOLERATED = java.util.Set.of("I3", "I8");

    private final EvalProperties properties;
    private final DatasetLoader datasetLoader;
    private final TaskRunner taskRunner;
    private final DetailReader detailReader;
    private final RuleScorer ruleScorer;
    private final JudgeScorer judgeScorer;
    private final JudgeClient judgeClient;
    private final ArtifactWriter artifactWriter;
    private final ObjectMapper objectMapper;
    private final InvariantChecker invariantChecker = new InvariantChecker();

    private volatile int exitCode = EvalExitCode.OK;

    public EvalRunner(EvalProperties properties,
                      DatasetLoader datasetLoader,
                      TaskRunner taskRunner,
                      DetailReader detailReader,
                      RuleScorer ruleScorer,
                      JudgeScorer judgeScorer,
                      JudgeClient judgeClient,
                      ArtifactWriter artifactWriter,
                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.datasetLoader = datasetLoader;
        this.taskRunner = taskRunner;
        this.detailReader = detailReader;
        this.ruleScorer = ruleScorer;
        this.judgeScorer = judgeScorer;
        this.judgeClient = judgeClient;
        this.artifactWriter = artifactWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        this.exitCode = execute();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private int execute() {
        EvalMode mode;
        try {
            mode = EvalMode.from(properties.getMode());
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            return EvalExitCode.INFRA;
        }
        if (mode == EvalMode.CALIBRATE) {
            return calibrate();
        }

        Instant startedAt = Instant.now();
        String runId = RUN_ID.format(startedAt);
        return mode == EvalMode.RESCORE ? rescore(startedAt, runId) : fullRun(mode, startedAt, runId);
    }

    // ---------------------------------------------------------------- full run

    private int fullRun(EvalMode mode, Instant startedAt, String runId) {
        DatasetLoader.LoadedDataset dataset;
        try {
            dataset = datasetLoader.load(properties);
        } catch (EvalInfraException e) {
            log.error("cannot load dataset: {}", e.getMessage());
            return EvalExitCode.INFRA;
        }

        boolean urlCheck = properties.getUrlCheck().isEnabled() && mode != EvalMode.SELF_TEST;
        boolean judgeEnabled = properties.getJudge().isEnabled() && mode == EvalMode.REAL;
        if (mode == EvalMode.SELF_TEST && properties.getJudge().isEnabled()) {
            log.warn("self-test forces the judge off (no external model calls)");
        }
        int configError = validateJudgeConfig(mode, judgeEnabled);
        if (configError != EvalExitCode.OK) {
            return configError;
        }

        log.info("eval run {} mode={} questions={} verifiedGold={} judge={} urlCheck={}",
                runId, mode.value(), dataset.questions().size(), dataset.verified(),
                judgeEnabled ? properties.getJudge().getModel() : "disabled", urlCheck);

        if (mode == EvalMode.ESTIMATE) {
            printEstimate(dataset, judgeEnabled);
            return EvalExitCode.OK;
        }

        RunOutcome outcome = executeAll(runId, dataset, urlCheck, judgeEnabled);
        List<QuestionResult> results = outcome.results();
        List<ClaimSample> claimSamples = collectClaimSamples(results);
        ConfidenceCalibration.Result confidence = ConfidenceCalibration.evaluate(
                claimSamples, 10, 0.8);
        boolean confidenceFailure = judgeEnabled
                && confidence.sufficient(properties.getConfidenceMinSamples())
                && confidence.ece() > properties.getConfidenceMaxEce();
        String depth = effectiveDepth();
        LabelOutcome labels = prepareLabels(runId, results, Path.of(properties.getLabelsDir(),
                runId + ".jsonl"), true);
        EvalMetrics metrics = EvalMetrics.of(results, dataset.verified());
        ThresholdEvaluator.Result thresholds = ThresholdEvaluator.evaluate(
                metrics, properties.getThresholds(), properties.isThresholdsEnabled());
        BaselineOutcome baseline = baseline(dataset, metrics, depth, judgeEnabled, true, true);

        int code = decide(results, outcome.infraFailure(), mode, judgeEnabled,
                labels.calibration(), thresholds, baseline.diff(), confidenceFailure);
        if (baseline.frozen() && code != EvalExitCode.OK) {
            log.warn("baseline was frozen from a run with exit={}; only freeze from a clean run",
                    code);
        }
        RunReport report = new RunReport(runId, mode, startedAt, Instant.now(), dataset, results,
                metrics, code, urlCheck, judgeEnabled, depth, labels.sampled(), labels.path(),
                labels.written(), labels.calibration(), judgeClient.counters(), thresholds,
                baseline.diff(), baseline.path(), baseline.frozen(), confidence, claimSamples, "");
        return writeAndLog(report);
    }

    // ---------------------------------------------------------------- rescore

    private int rescore(Instant startedAt, String runId) {
        String from = properties.getRescoreFrom();
        if (from == null || from.isBlank()) {
            log.error("rescore needs --rescore-from=<eval/runs/<runId>>");
            return EvalExitCode.INFRA;
        }
        Path sourceDir = Path.of(from);
        if (!Files.isDirectory(sourceDir)) {
            log.error("source run not found: {}", sourceDir.toAbsolutePath());
            return EvalExitCode.INFRA;
        }
        JsonNode sourceManifest;
        try {
            sourceManifest = objectMapper.readTree(sourceDir.resolve("run.json").toFile());
        } catch (IOException e) {
            log.error("cannot read {}/run.json: {}", sourceDir, e.getMessage());
            return EvalExitCode.INFRA;
        }
        String datasetPath = sourceManifest.path("dataset").path("path").asText("");
        if (!datasetPath.isBlank()) {
            properties.setDataset(datasetPath);
        }
        DatasetLoader.LoadedDataset dataset;
        try {
            dataset = datasetLoader.load(properties);
        } catch (EvalInfraException e) {
            log.error("cannot load dataset: {}", e.getMessage());
            return EvalExitCode.INFRA;
        }
        String sourceSha = sourceManifest.path("dataset").path("sha256").asText("");
        if (!sourceSha.isBlank() && !sourceSha.equals(dataset.sha256())) {
            log.warn("dataset changed since the source run ({} → {}); quality metrics may drift",
                    shortSha(sourceSha), shortSha(dataset.sha256()));
        }

        Map<String, String> taskIds;
        try {
            taskIds = readTaskIndex(sourceDir.resolve("task-index.json"));
        } catch (EvalInfraException e) {
            log.error(e.getMessage());
            return EvalExitCode.INFRA;
        }
        if (taskIds.isEmpty()) {
            log.error("source run has no task-index.json; nothing to rescore");
            return EvalExitCode.INFRA;
        }
        List<EvalQuestion> questions = dataset.questions().stream()
                .filter(question -> taskIds.containsKey(question.id()))
                .toList();
        if (questions.isEmpty()) {
            log.error("no dataset question matches the source run's task index");
            return EvalExitCode.INFRA;
        }

        String depth = sourceManifest.path("config").path("depth").asText("");
        if (depth.isBlank()) {
            depth = effectiveDepth();
        }
        boolean urlCheck = properties.getUrlCheck().isEnabled();
        boolean judgeEnabled = properties.getJudge().isEnabled();
        int configError = validateJudgeConfig(EvalMode.RESCORE, judgeEnabled);
        if (configError != EvalExitCode.OK) {
            return configError;
        }

        log.info("rescore run {} from={} questions={} judge={}",
                runId, sourceDir, questions.size(),
                judgeEnabled ? properties.getJudge().getModel() : "disabled");

        AtomicLong spentMicros = new AtomicLong();
        AtomicBoolean infraFailure = new AtomicBoolean(false);
        List<QuestionResult> results = rescoreAll(questions, taskIds, depth, urlCheck,
                judgeEnabled, spentMicros, infraFailure);

        String sourceRunId = sourceDir.getFileName() == null ? "" : sourceDir.getFileName().toString();
        LabelOutcome labels = prepareLabels(runId, results,
                Path.of(properties.getLabelsDir(), sourceRunId + ".jsonl"), false);
        List<ClaimSample> claimSamples = collectClaimSamples(results);
        ConfidenceCalibration.Result confidence = ConfidenceCalibration.evaluate(
                claimSamples, 10, 0.8);
        boolean confidenceFailure = judgeEnabled
                && confidence.sufficient(properties.getConfidenceMinSamples())
                && confidence.ece() > properties.getConfidenceMaxEce();
        EvalMetrics metrics = EvalMetrics.of(results, dataset.verified());
        ThresholdEvaluator.Result thresholds = ThresholdEvaluator.evaluate(
                metrics, properties.getThresholds(), properties.isThresholdsEnabled());
        // A rescore does not re-run generation, so latency/cost are not comparable.
        BaselineOutcome baseline = baseline(dataset, metrics, depth, judgeEnabled, false, false);

        int code = decide(results, infraFailure.get(), EvalMode.RESCORE, judgeEnabled,
                labels.calibration(), thresholds, baseline.diff(), confidenceFailure);
        RunReport report = new RunReport(runId, EvalMode.RESCORE, startedAt, Instant.now(), dataset,
                results, metrics, code, urlCheck, judgeEnabled, depth, labels.sampled(),
                labels.path(), false, labels.calibration(), judgeClient.counters(), thresholds,
                baseline.diff(), baseline.path(), baseline.frozen(), confidence, claimSamples,
                sourceDir.toString());
        return writeAndLog(report);
    }

    private List<QuestionResult> rescoreAll(List<EvalQuestion> questions, Map<String, String> taskIds,
                                            String depth, boolean urlCheck, boolean judgeEnabled,
                                            AtomicLong spentMicros, AtomicBoolean infraFailure) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, properties.getParallel()));
        List<Future<QuestionResult>> futures = new ArrayList<>(questions.size());
        try {
            for (EvalQuestion question : questions) {
                String taskId = taskIds.get(question.id());
                futures.add(pool.submit(() -> rescoreOne(taskId, question, depth, urlCheck,
                        judgeEnabled, spentMicros, infraFailure)));
            }
            List<QuestionResult> results = new ArrayList<>(questions.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(QuestionResult.failed(questions.get(i).id(), questions.get(i).split(),
                            null, depth, 0, 0, 0, questions.get(i).verified(), "interrupted"));
                    infraFailure.set(true);
                } catch (ExecutionException e) {
                    results.add(QuestionResult.failed(questions.get(i).id(), questions.get(i).split(),
                            null, depth, 0, 0, 0, questions.get(i).verified(),
                            String.valueOf(e.getCause())));
                    infraFailure.set(true);
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private QuestionResult rescoreOne(String taskId, EvalQuestion question, String depth,
                                      boolean urlCheck, boolean judgeEnabled,
                                      AtomicLong spentMicros, AtomicBoolean infraFailure) {
        if (taskId == null || taskId.isBlank()) {
            return QuestionResult.skipped(question.id(), question.split(), depth,
                    question.verified(), "no taskId in source run");
        }
        try {
            TaskRunner.Status status = taskRunner.status(taskId);
            if (!"SUCCEEDED".equals(status.status())) {
                return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                        0, status.costMicros(), status.tokens(), question.verified(),
                        "source task ended as " + status.status());
            }
            TaskRunner.ReportEnvelope report = taskRunner.fetchReport(taskId);
            DbSnapshot snapshot = detailReader.load(taskId);
            List<InvariantChecker.Violation> violations = invariantChecker.check(
                    report.document(), report.markdown(), snapshot, status.warnings());
            RuleScorer.RuleResult rule = ruleScorer.score(report.document(), snapshot, urlCheck);
            JudgeResult judge = judgeEnabled
                    ? judgeScorer.score(question, report.document())
                    : JudgeResult.empty();
            if (judgeEnabled) {
                spentMicros.addAndGet(Math.max(0, judge.costMicros()));
            }
            int claims = countClaims(report);
            int citations = countCitations(report);
            log.info("[{}] rescored claims={} citations={} CAR={} GCR={} violations={}",
                    question.id(), claims, citations,
                    judgeEnabled ? String.format(Locale.ROOT, "%.3f", judge.carStrict()) : "-",
                    judgeEnabled ? String.format(Locale.ROOT, "%.3f", judge.gcr()) : "-",
                    violations.size());
            // Latency is 0 on purpose: no generation happened in this run.
            return new QuestionResult(question.id(), question.split(), taskId, "SUCCEEDED", null,
                    depth, 0, status.costMicros(), status.tokens(), claims, citations,
                    rule, judge, violations, question.verified(), false);
        } catch (EvalInfraException e) {
            infraFailure.set(true);
            log.error("[{}] rescore infra failure: {}", question.id(), e.getMessage());
            return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                    0, 0, 0, question.verified(), e.getMessage());
        } catch (RuntimeException e) {
            infraFailure.set(true);
            log.error("[{}] rescore failed: {}", question.id(), e.getMessage(), e);
            return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                    0, 0, 0, question.verified(), String.valueOf(e));
        }
    }

    private Map<String, String> readTaskIndex(Path file) {
        Map<String, String> taskIds = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return taskIds;
        }
        try {
            JsonNode array = objectMapper.readTree(file.toFile());
            if (array.isArray()) {
                for (JsonNode node : array) {
                    String questionId = node.path("questionId").asText("");
                    String taskId = node.path("taskId").asText("");
                    if (!questionId.isBlank() && !taskId.isBlank()) {
                        taskIds.put(questionId, taskId);
                    }
                }
            }
        } catch (IOException e) {
            throw new EvalInfraException("cannot read task index " + file + ": " + e.getMessage(), e);
        }
        return taskIds;
    }

    // ---------------------------------------------------------------- shared

    private int validateJudgeConfig(EvalMode mode, boolean judgeEnabled) {
        if (!judgeEnabled) {
            return EvalExitCode.OK;
        }
        if (mode == EvalMode.SELF_TEST) {
            return EvalExitCode.OK;
        }
        if (properties.getJudge().getApiKey() == null || properties.getJudge().getApiKey().isBlank()) {
            log.error("judge enabled but no key: set DRA_EVAL_JUDGE_API_KEY or --judge-api-key");
            return EvalExitCode.INFRA;
        }
        return EvalExitCode.OK;
    }

    private LabelOutcome prepareLabels(String runId, List<QuestionResult> results,
                                       Path defaultLabelsPath, boolean writeTemplate) {
        List<LabelItem> candidates = new ArrayList<>();
        for (QuestionResult result : results) {
            candidates.addAll(result.judge().j1Candidates());
            candidates.addAll(result.judge().j2Candidates());
        }
        List<LabelItem> sample = LabelSampler.sample(candidates,
                properties.getKappaSampleJ1(), properties.getKappaSampleJ2());
        String labelsPath = properties.getLabels() == null || properties.getLabels().isBlank()
                ? defaultLabelsPath.toString()
                : properties.getLabels();
        boolean written = writeTemplate
                && Calibration.writeTemplate(Path.of(labelsPath), sample, objectMapper);
        Calibration.Report calibration = calibration(labelsPath);
        return new LabelOutcome(sample, labelsPath, written, calibration);
    }

    private Calibration.Report calibration(String labelsPath) {
        Path file = Path.of(labelsPath);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Calibration.compute(Calibration.read(file, objectMapper));
    }

    private BaselineOutcome baseline(DatasetLoader.LoadedDataset dataset, EvalMetrics metrics,
                                     String depth, boolean judgeEnabled, boolean includePerformance,
                                     boolean allowFreeze) {
        String path = properties.getBaseline();
        if (path == null || path.isBlank()) {
            return new BaselineOutcome(null, "", false);
        }
        Path file = Path.of(path);
        Baseline.DatasetFingerprint fingerprint =
                new Baseline.DatasetFingerprint(dataset.sha256(), dataset.datasetVersions());
        Baseline baseline = Baseline.of(metrics, fingerprint, depth, judgeEnabled,
                properties.getJudge().getModel());
        if (allowFreeze && properties.isFreezeBaseline()) {
            baseline.write(file, objectMapper);
            log.info("baseline frozen: {}", file.toAbsolutePath());
            return new BaselineOutcome(null, path, true);
        }
        if (!allowFreeze && properties.isFreezeBaseline()) {
            log.warn("ignoring --freeze-baseline in rescore (no generation metrics to freeze)");
        }
        Optional<Baseline> existing;
        try {
            existing = Baseline.read(file, objectMapper);
        } catch (RuntimeException e) {
            log.error("cannot read baseline: {}", e.getMessage());
            return new BaselineOutcome(null, path, false);
        }
        if (existing.isEmpty()) {
            log.warn("baseline not found: {}; run with --freeze-baseline to create it",
                    file.toAbsolutePath());
            return new BaselineOutcome(null, path, false);
        }
        BaselineComparator.Diff diff = BaselineComparator.compare(existing.get(), metrics,
                fingerprint, depth, judgeEnabled, properties.getJudge().getModel(), includePerformance);
        if (!diff.comparable()) {
            log.warn("baseline not comparable: {}", diff.reason());
        } else if (diff.regressed()) {
            log.error("baseline regressions: {}", diff.regressions().stream()
                    .map(BaselineComparator::describe).toList());
        } else {
            log.info("baseline: no regression");
        }
        return new BaselineOutcome(diff, path, false);
    }

    private int decide(List<QuestionResult> results, boolean infraFailure, EvalMode mode,
                       boolean judgeEnabled, Calibration.Report calibration,
                       ThresholdEvaluator.Result thresholds, BaselineComparator.Diff diff,
                       boolean confidenceFailure) {
        boolean anyBlockingViolation = results.stream()
                .flatMap(r -> r.violations().stream())
                .anyMatch(v -> !(mode == EvalMode.SELF_TEST && SELF_TEST_TOLERATED.contains(v.id())));
        boolean budgetExceeded = results.stream().anyMatch(r -> r.skipped()
                && r.error() != null && r.error().startsWith("cost cap"));

        if (judgeEnabled && calibration != null && calibration.labeled()
                && calibration.minKappa() < properties.getKappaThreshold()) {
            log.error("judge-human agreement below threshold: minκ={} < {}",
                    String.format(Locale.ROOT, "%.3f", calibration.minKappa()),
                    properties.getKappaThreshold());
        }
        if (confidenceFailure) {
            log.error("confidence calibration failed: ECE > {}", properties.getConfidenceMaxEce());
        }
        if (thresholds != null && thresholds.enabled() && !thresholds.passed()) {
            log.error("thresholds failed: {}", thresholds.messages());
        }
        if (diff != null && diff.regressed()) {
            log.error("baseline regression: {}", diff.regressions().stream()
                    .map(BaselineComparator::describe).toList());
        }

        int code = RunGate.decide(new RunGate.Inputs(anyBlockingViolation, infraFailure,
                budgetExceeded, judgeEnabled, calibration, properties.getKappaThreshold(),
                confidenceFailure, thresholds, diff));
        if (code != EvalExitCode.OK) {
            return code;
        }
        long verified = results.stream().filter(QuestionResult::goldVerified).count();
        if (verified < results.size()) {
            log.warn("{} of {} questions have unverified gold; headline metrics are diagnostic only",
                    results.size() - verified, results.size());
        }
        return EvalExitCode.OK;
    }

    private int writeAndLog(RunReport report) {
        try {
            Path runDir = artifactWriter.write(report);
            log.info("artifacts: {}", runDir.toAbsolutePath());
        } catch (RuntimeException e) {
            log.error("cannot write artifacts: {}", e.getMessage());
            return EvalExitCode.INFRA;
        }
        EvalMetrics metrics = report.metrics();
        if (report.templateWritten()) {
            log.info("label template written: {} (fill humanVerdict, then --mode=calibrate)",
                    report.labelsPath());
        }
        if (report.calibration() != null) {
            log.info("calibration: {} labels, minκ={}", report.calibration().labeledTotal(),
                    String.format(Locale.ROOT, "%.3f", report.calibration().minKappa()));
        }
        if (report.thresholds() != null && report.thresholds().enabled() && !report.thresholds().passed()) {
            log.info("threshold failures: {}", report.thresholds().messages());
        }
        log.info("done: {}/{} succeeded, judgeCalls={} cacheHits={} violations={} exit={}",
                metrics.succeeded(), metrics.questions(), metrics.judgeCalls(),
                metrics.judgeCacheHits(), metrics.invariantViolations(), report.exitCode());
        return report.exitCode();
    }

    // ---------------------------------------------------------------- helpers

    private int calibrate() {
        String labelsPath = properties.getLabels();
        if (labelsPath == null || labelsPath.isBlank()) {
            log.error("calibrate needs --labels=<file> (the template written by a run)");
            return EvalExitCode.INFRA;
        }
        Path file = Path.of(labelsPath);
        if (!Files.isRegularFile(file)) {
            log.error("label file not found: {}", file.toAbsolutePath());
            return EvalExitCode.INFRA;
        }
        Calibration.Report report = Calibration.compute(Calibration.read(file, objectMapper));
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(Path.of(labelsPath + ".kappa.json").toFile(), report);
        } catch (IOException e) {
            log.warn("cannot write kappa report: {}", e.getMessage());
        }
        if (!report.labeled()) {
            log.warn("no humanVerdict filled yet in {}", file.toAbsolutePath());
            return EvalExitCode.THRESHOLD;
        }
        for (Map.Entry<String, Calibration.KindResult> entry : report.byKind().entrySet()) {
            Calibration.KindResult kind = entry.getValue();
            log.info("{}: n={} agreement={} observed={} kappa={}",
                    entry.getKey(), kind.labeled(), kind.agreement(),
                    String.format(Locale.ROOT, "%.3f", kind.observed()),
                    String.format(Locale.ROOT, "%.3f", kind.kappa()));
        }
        boolean trusted = report.minKappa() >= properties.getKappaThreshold();
        log.info("min kappa {} vs threshold {} → judge {}",
                String.format(Locale.ROOT, "%.3f", report.minKappa()),
                String.format(Locale.ROOT, "%.2f", properties.getKappaThreshold()),
                trusted ? "trusted" : "NOT trusted");
        return trusted ? EvalExitCode.OK : EvalExitCode.THRESHOLD;
    }

    private RunOutcome executeAll(String runId, DatasetLoader.LoadedDataset dataset,
                                  boolean urlCheck, boolean judgeEnabled) {
        List<EvalQuestion> questions = dataset.questions();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, properties.getParallel()));
        AtomicLong spentMicros = new AtomicLong();
        AtomicBoolean infraFailure = new AtomicBoolean(false);
        List<Future<QuestionResult>> futures = new ArrayList<>(questions.size());
        try {
            for (EvalQuestion question : questions) {
                futures.add(pool.submit(() -> runOne(runId, question, urlCheck, judgeEnabled,
                        spentMicros, infraFailure)));
            }
            List<QuestionResult> results = new ArrayList<>(questions.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(QuestionResult.failed(questions.get(i).id(), questions.get(i).split(),
                            null, depthOf(questions.get(i)), 0, 0, 0, questions.get(i).verified(),
                            "interrupted"));
                    infraFailure.set(true);
                } catch (ExecutionException e) {
                    results.add(QuestionResult.failed(questions.get(i).id(), questions.get(i).split(),
                            null, depthOf(questions.get(i)), 0, 0, 0, questions.get(i).verified(),
                            String.valueOf(e.getCause())));
                    infraFailure.set(true);
                }
            }
            return new RunOutcome(results, infraFailure.get());
        } finally {
            pool.shutdown();
        }
    }

    private QuestionResult runOne(String runId, EvalQuestion question, boolean urlCheck,
                                  boolean judgeEnabled, AtomicLong spentMicros,
                                  AtomicBoolean infraFailure) {
        String depth = depthOf(question);
        if (spentMicros.get() >= properties.maxCostMicros()) {
            return QuestionResult.skipped(question.id(), question.split(), depth, question.verified(),
                    "cost cap reached (¥" + properties.getMaxCostCny() + ")");
        }

        long startNanos = System.nanoTime();
        String taskId = null;
        try {
            taskId = taskRunner.submit(runId, question, depth);
            TaskRunner.Status status = taskRunner.awaitTerminal(taskId);
            long latencyMs = elapsedMillis(startNanos);
            spentMicros.addAndGet(Math.max(0, status.costMicros()));

            if (!"SUCCEEDED".equals(status.status())) {
                String error = status.errorCode() == null
                        ? "task ended as " + status.status()
                        : status.errorCode() + ": " + status.errorMessage();
                log.warn("[{}] {} ({})", question.id(), status.status(), error);
                return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                        latencyMs, status.costMicros(), status.tokens(), question.verified(), error);
            }

            TaskRunner.ReportEnvelope report = taskRunner.fetchReport(taskId);
            DbSnapshot snapshot = detailReader.load(taskId);
            List<InvariantChecker.Violation> violations = invariantChecker.check(
                    report.document(), report.markdown(), snapshot, status.warnings());
            RuleScorer.RuleResult rule = ruleScorer.score(report.document(), snapshot, urlCheck);
            JudgeResult judge = judgeEnabled
                    ? judgeScorer.score(question, report.document())
                    : JudgeResult.empty();
            if (judgeEnabled) {
                spentMicros.addAndGet(Math.max(0, judge.costMicros()));
            }

            int claims = countClaims(report);
            int citations = countCitations(report);
            log.info("[{}] SUCCEEDED {}s ¥{} claims={} citations={} anchor={} CAR={} GCR={} "
                            + "judgeCalls={} violations={}",
                    question.id(), latencyMs / 1000,
                    String.format(Locale.ROOT, "%.4f", status.costMicros() / 1_000_000.0),
                    claims, citations, String.format(Locale.ROOT, "%.3f", rule.anchorRate()),
                    judgeEnabled ? String.format(Locale.ROOT, "%.3f", judge.carStrict()) : "-",
                    judgeEnabled ? String.format(Locale.ROOT, "%.3f", judge.gcr()) : "-",
                    judge.j1Calls() + judge.j2Calls() + judge.j3Calls(), violations.size());
            return new QuestionResult(question.id(), question.split(), taskId, "SUCCEEDED", null,
                    depth, latencyMs, status.costMicros(), status.tokens(), claims, citations,
                    rule, judge, violations, question.verified(), false);
        } catch (EvalInfraException e) {
            infraFailure.set(true);
            log.error("[{}] infra failure: {}", question.id(), e.getMessage());
            return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                    elapsedMillis(startNanos), 0, 0, question.verified(), e.getMessage());
        } catch (RuntimeException e) {
            infraFailure.set(true);
            log.error("[{}] failed: {}", question.id(), e.getMessage(), e);
            return QuestionResult.failed(question.id(), question.split(), taskId, depth,
                    elapsedMillis(startNanos), 0, 0, question.verified(), String.valueOf(e));
        }
    }

    private void printEstimate(DatasetLoader.LoadedDataset dataset, boolean judgeEnabled) {
        String depth = properties.getDepth() == null ? "" : properties.getDepth().toUpperCase(Locale.ROOT);
        double perTask = switch (depth) {
            case "STANDARD" -> 0.50;
            case "DEEP" -> 1.50;
            default -> 0.15;
        };
        int count = dataset.questions().size();
        double judgePerTask = judgeEnabled ? 0.10 : 0.0;
        log.info("estimate: {} questions, depth={}, generation ~¥{}, judge ~¥{} (J1 per claim + "
                        + "J2 per question + J3 on exact), cap ¥{}",
                count, depth.isEmpty() ? "per-question" : depth,
                String.format(Locale.ROOT, "%.2f", perTask * count),
                String.format(Locale.ROOT, "%.2f", judgePerTask * count),
                properties.getMaxCostCny());
    }

    private String effectiveDepth() {
        String configured = properties.getDepth();
        return configured == null || configured.isBlank()
                ? "per-question" : configured.toUpperCase(Locale.ROOT);
    }

    private String depthOf(EvalQuestion question) {
        String configured = properties.getDepth();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return question.depth() == null || question.depth().isBlank() ? "QUICK" : question.depth();
    }

    private static List<ClaimSample> collectClaimSamples(List<QuestionResult> results) {
        List<ClaimSample> samples = new ArrayList<>();
        for (QuestionResult result : results) {
            samples.addAll(result.judge().claimSamples());
        }
        return samples;
    }

    private static int countClaims(TaskRunner.ReportEnvelope report) {        return report.document().sections().stream()
                .mapToInt(section -> section.claims().size())
                .sum();
    }

    private static int countCitations(TaskRunner.ReportEnvelope report) {
        return report.document().sections().stream()
                .flatMap(section -> section.claims().stream())
                .mapToInt(claim -> claim.citations().size())
                .sum();
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String shortSha(String sha) {
        return sha == null || sha.length() < 12 ? String.valueOf(sha) : sha.substring(0, 12);
    }

    private record RunOutcome(List<QuestionResult> results, boolean infraFailure) {
    }

    private record LabelOutcome(List<LabelItem> sampled, String path, boolean written,
                                Calibration.Report calibration) {
    }

    private record BaselineOutcome(BaselineComparator.Diff diff, String path, boolean frozen) {
    }
}
