package com.dra.eval.calibration;

import com.dra.eval.judge.LabelItem;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cohen's kappa between judge verdicts and human labels. Labels are written as a template
 * by each run and filled in offline; without labels the judge numbers stay "uncalibrated".
 */
public final class Calibration {

    private Calibration() {
    }

    public record Label(
            String kind,
            String questionId,
            String subjectId,
            String prompt,
            String judgeVerdict,
            String humanVerdict) {
    }

    public record KindResult(String kind, int labeled, int agreement,
                             double observed, double expected, double kappa) {
    }

    public record Report(String status, int labeledTotal, double minKappa,
                         Map<String, KindResult> byKind) {

        public boolean labeled() {
            return "LABELED".equals(status);
        }
    }

    public static List<Label> read(Path file, ObjectMapper objectMapper) {
        List<Label> labels = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return labels;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    labels.add(objectMapper.readValue(line, Label.class));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read labels " + file + ": " + e.getMessage(), e);
        }
        return labels;
    }

    public static Report compute(List<Label> labels) {
        Map<String, List<Label>> byKind = new LinkedHashMap<>();
        for (Label label : labels) {
            if (label.humanVerdict() != null && !label.humanVerdict().isBlank()
                    && label.judgeVerdict() != null && !label.judgeVerdict().isBlank()) {
                byKind.computeIfAbsent(label.kind(), k -> new ArrayList<>()).add(label);
            }
        }
        Map<String, KindResult> results = new LinkedHashMap<>();
        int labeledTotal = 0;
        double minKappa = 1.0;
        for (Map.Entry<String, List<Label>> entry : byKind.entrySet()) {
            List<Label> kindLabels = entry.getValue();
            KindResult result = kappa(entry.getKey(), kindLabels);
            results.put(entry.getKey(), result);
            labeledTotal += kindLabels.size();
            minKappa = Math.min(minKappa, result.kappa());
        }
        String status = labeledTotal == 0 ? "UNLABELED" : "LABELED";
        return new Report(status, labeledTotal, labeledTotal == 0 ? 0.0 : minKappa, results);
    }

    static KindResult kappa(String kind, List<Label> labels) {
        int n = labels.size();
        if (n == 0) {
            return new KindResult(kind, 0, 0, 0.0, 0.0, 0.0);
        }
        int agreement = 0;
        Map<String, Integer> judgeCounts = new LinkedHashMap<>();
        Map<String, Integer> humanCounts = new LinkedHashMap<>();
        Set<String> categories = new java.util.LinkedHashSet<>();
        for (Label label : labels) {
            String judge = label.judgeVerdict().trim().toUpperCase(java.util.Locale.ROOT);
            String human = label.humanVerdict().trim().toUpperCase(java.util.Locale.ROOT);
            if (judge.equals(human)) {
                agreement++;
            }
            judgeCounts.merge(judge, 1, Integer::sum);
            humanCounts.merge(human, 1, Integer::sum);
            categories.add(judge);
            categories.add(human);
        }
        double observed = (double) agreement / n;
        double expected = 0.0;
        for (String category : categories) {
            expected += ((double) judgeCounts.getOrDefault(category, 0) / n)
                    * ((double) humanCounts.getOrDefault(category, 0) / n);
        }
        double kappa = expected >= 1.0 ? (observed >= 1.0 ? 1.0 : 0.0)
                : (observed - expected) / (1.0 - expected);
        return new KindResult(kind, n, agreement, observed, expected, kappa);
    }

    /** Writes a label template once; refuses to overwrite a file a human may have edited. */
    public static boolean writeTemplate(Path file, List<LabelItem> items,
                                        ObjectMapper objectMapper) {
        if (Files.exists(file) || items.isEmpty()) {
            return false;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder sb = new StringBuilder();
            for (LabelItem item : items) {
                sb.append(objectMapper.writeValueAsString(item)).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("cannot write label template " + file, e);
        }
    }
}
