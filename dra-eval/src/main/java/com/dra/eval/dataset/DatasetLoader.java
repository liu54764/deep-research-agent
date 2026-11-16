package com.dra.eval.dataset;

import com.dra.common.hash.Hashing;
import com.dra.eval.config.EvalProperties;
import com.dra.eval.support.EvalInfraException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads {@code eval/dataset/questions.jsonl} and applies split / limit filters. */
@Component
public class DatasetLoader {

    private static final Set<String> GOLD_TYPES = Set.of("exact", "keypoints");

    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LoadedDataset load(EvalProperties properties) {
        Path path = Path.of(properties.getDataset());
        if (!Files.isRegularFile(path)) {
            throw new EvalInfraException("dataset not found: " + path.toAbsolutePath());
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new EvalInfraException("cannot read dataset " + path, e);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<EvalQuestion> all = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                EvalQuestion question = objectMapper.readValue(line, EvalQuestion.class);
                validate(question, i + 1);
                all.add(question);
            } catch (IOException e) {
                throw new EvalInfraException("dataset line " + (i + 1) + " is not valid JSON: "
                        + e.getMessage(), e);
            }
        }

        String split = properties.getSplit() == null ? "" : properties.getSplit().trim().toLowerCase(Locale.ROOT);
        List<EvalQuestion> filtered = all.stream()
                .filter(q -> split.isEmpty() || "all".equals(split)
                        || split.equalsIgnoreCase(q.split()))
                .toList();
        if (properties.getLimit() > 0 && filtered.size() > properties.getLimit()) {
            filtered = filtered.subList(0, properties.getLimit());
        }
        if (filtered.isEmpty()) {
            throw new EvalInfraException("no questions matched split='" + properties.getSplit()
                    + "' (dataset has " + all.size() + ")");
        }
        return new LoadedDataset(path.toString(), Hashing.sha256Hex(bytes), all.size(), filtered);
    }

    private void validate(EvalQuestion question, int line) {
        if (question.id() == null || question.id().isBlank()) {
            throw new EvalInfraException("dataset line " + line + ": missing id");
        }
        if (question.question() == null || question.question().isBlank()) {
            throw new EvalInfraException("dataset line " + line + " (" + question.id() + "): missing question");
        }
        if (question.gold() == null || !GOLD_TYPES.contains(question.gold().type())) {
            throw new EvalInfraException("dataset line " + line + " (" + question.id()
                    + "): gold.type must be exact|keypoints");
        }
    }

    /** {@code total} counts everything in the file; {@code questions} is post-filter. */
    public record LoadedDataset(String path, String sha256, int total, List<EvalQuestion> questions) {

        public long verified() {
            return questions.stream().filter(EvalQuestion::verified).count();
        }

        public List<String> datasetVersions() {
            return questions.stream().map(EvalQuestion::datasetVersion).distinct().toList();
        }
    }
}
