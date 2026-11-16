package com.dra.eval.dataset;

import com.dra.eval.config.EvalProperties;
import com.dra.eval.support.EvalInfraException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DatasetLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DatasetLoader loader = new DatasetLoader(mapper);

    @Test
    void loadsRepositoryDataset() {
        Path path = repositoryDataset();
        assumeTrue(path != null, "repository dataset not available from this working directory");

        EvalProperties properties = new EvalProperties();
        properties.setDataset(path.toString());
        properties.setSplit("regression");

        DatasetLoader.LoadedDataset dataset = loader.load(properties);

        assertEquals(8, dataset.total());
        assertEquals(8, dataset.questions().size());
        assertEquals(8, dataset.verified(), "all 8 questions were fact-checked by 2026-11-16");
        assertNotNull(dataset.sha256());
        assertEquals(64, dataset.sha256().length());
        assertEquals(1, dataset.datasetVersions().size());
        assertTrue(dataset.questions().stream().allMatch(q -> q.id().startsWith("q-")));
    }

    @Test
    void filtersBySplitAndLimit(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("questions.jsonl");
        Files.writeString(file, String.join("\n",
                entry("q-a", "regression", "keypoints", ""),
                entry("q-b", "dev", "keypoints", ""),
                entry("q-c", "regression", "exact", "")
        ) + "\n", StandardCharsets.UTF_8);

        EvalProperties properties = new EvalProperties();
        properties.setDataset(file.toString());
        properties.setSplit("regression");
        properties.setLimit(1);

        DatasetLoader.LoadedDataset dataset = loader.load(properties);

        assertEquals(3, dataset.total());
        assertEquals(1, dataset.questions().size());
        assertEquals("q-a", dataset.questions().get(0).id());
        assertEquals(1, dataset.datasetVersions().size());
    }

    @Test
    void rejectsUnknownGoldType(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("questions.jsonl");
        Files.writeString(file, entry("q-a", "regression", "vibes", "") + "\n", StandardCharsets.UTF_8);

        EvalProperties properties = new EvalProperties();
        properties.setDataset(file.toString());

        EvalInfraException error = assertThrows(EvalInfraException.class, () -> loader.load(properties));
        assertTrue(error.getMessage().contains("gold.type"));
    }

    @Test
    void rejectsMissingQuestion(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("questions.jsonl");
        Files.writeString(file, "{\"id\":\"q-a\",\"datasetVersion\":\"2026-11-15\","
                + "\"split\":\"regression\",\"category\":\"x\",\"question\":\"\","
                + "\"language\":\"zh\",\"depth\":\"QUICK\",\"contested\":false,"
                + "\"gold\":{\"type\":\"keypoints\",\"verified\":false,\"keyPoints\":[]}}\n",
                StandardCharsets.UTF_8);

        EvalProperties properties = new EvalProperties();
        properties.setDataset(file.toString());

        assertThrows(EvalInfraException.class, () -> loader.load(properties));
    }

    @Test
    void rejectsEmptySelection(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("questions.jsonl");
        Files.writeString(file, entry("q-a", "dev", "keypoints", "") + "\n", StandardCharsets.UTF_8);

        EvalProperties properties = new EvalProperties();
        properties.setDataset(file.toString());
        properties.setSplit("test");

        EvalInfraException error = assertThrows(EvalInfraException.class, () -> loader.load(properties));
        assertTrue(error.getMessage().contains("no questions matched"));
    }

    /** One JSONL line: newlines are not allowed inside a JSONL record. */
    private static String entry(String id, String split, String goldType, String question) {
        String safeQuestion = question.isBlank() ? "what is the answer to " + id : question;
        return "{\"id\":\"" + id + "\",\"datasetVersion\":\"2026-11-15\",\"split\":\"" + split
                + "\",\"category\":\"x\",\"question\":\"" + safeQuestion
                + "\",\"language\":\"zh\",\"depth\":\"QUICK\",\"contested\":false,\"gold\":{\"type\":\""
                + goldType + "\",\"verified\":false,\"keyPoints\":[]}}";
    }

    private static Path repositoryDataset() {
        Path fromRoot = Path.of("eval/dataset/questions.jsonl");
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        Path fromModule = Path.of("../eval/dataset/questions.jsonl");
        return Files.isRegularFile(fromModule) ? fromModule : null;
    }
}
