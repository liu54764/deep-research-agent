package com.dra.eval.report;

import com.dra.eval.config.EvalProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Frozen reference numbers for regression comparison. Only the stable metric subset is
 * stored, plus the fingerprint that decides whether two runs are comparable at all.
 */
public record Baseline(
        String baselineVersion,
        String createdAt,
        String datasetSha256,
        List<String> datasetVersions,
        int questions,
        String depth,
        boolean judgeEnabled,
        String judgeModel,
        Snapshot metrics) {

    public Baseline {
        datasetVersions = datasetVersions == null ? List.of() : List.copyOf(datasetVersions);
    }

    public record Snapshot(
            double carStrict,
            double carWeighted,
            double hfr,
            double csr,
            double gcr,
            double contradictionRate,
            double claimPrecision,
            double anchorRate,
            double urlLiveRate,
            long latencyP50Ms,
            long latencyP95Ms,
            long costMicros,
            long judgeCostMicros,
            double successRate) {

        public static Snapshot of(EvalMetrics metrics) {
            return new Snapshot(
                    metrics.carStrict(), metrics.carWeighted(), metrics.hfr(), metrics.csr(),
                    metrics.gcr(), metrics.contradictionRate(), metrics.claimPrecision(),
                    metrics.anchorRate(), metrics.urlLiveRate(),
                    metrics.latencyP50Ms(), metrics.latencyP95Ms(),
                    metrics.costMicros(), metrics.judgeCostMicros(),
                    metrics.questions() == 0 ? 0.0
                            : (double) metrics.succeeded() / metrics.questions());
        }
    }

    public static Baseline of(EvalMetrics metrics, DatasetFingerprint dataset,
                              String depth, boolean judgeEnabled, String judgeModel) {
        return new Baseline("v1", Instant.now().toString(), dataset.sha256(),
                dataset.versions(), metrics.questions(), depth == null ? "" : depth,
                judgeEnabled, judgeModel == null ? "" : judgeModel, Snapshot.of(metrics));
    }

    public record DatasetFingerprint(String sha256, List<String> versions) {
    }

    public static Optional<Baseline> read(Path file, ObjectMapper objectMapper) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), Baseline.class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read baseline " + file + ": " + e.getMessage(), e);
        }
    }

    public void write(Path file, ObjectMapper objectMapper) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write baseline " + file, e);
        }
    }
}
