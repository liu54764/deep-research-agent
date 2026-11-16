package com.dra.eval.judge;

import com.dra.eval.config.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * File-backed judge cache. Key = prompt version + system + user (the full request), so a
 * prompt edit or a different claim text is guaranteed to miss. Successful verdicts only:
 * invalid model output is retried and never cached.
 */
@Component
public class JudgeCache {

    private static final Logger log = LoggerFactory.getLogger(JudgeCache.class);

    private final ObjectMapper objectMapper;
    private final Path directory;

    public JudgeCache(ObjectMapper objectMapper, EvalProperties properties) {
        this.objectMapper = objectMapper;
        this.directory = Path.of(properties.getCacheDir());
    }

    public Optional<Entry> get(String key) {
        Path file = directory.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), Entry.class));
        } catch (IOException e) {
            log.warn("ignoring unreadable judge cache entry {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void put(String key, Entry entry) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(key + ".json");
            Path temp = Files.createTempFile(directory, key, ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entry);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // A cache write must never fail an eval run.
            log.warn("cannot write judge cache entry {}: {}", key, e.getMessage());
        }
    }

    public Path directory() {
        return directory;
    }

    public record Entry(
            String promptVersion,
            String promptSha256,
            String model,
            JsonNode result,
            long promptTokens,
            long completionTokens,
            long costMicros,
            String createdAt) {

        public static Entry of(String promptVersion, String promptSha256, String model,
                               JsonNode result, long promptTokens, long completionTokens,
                               long costMicros) {
            return new Entry(promptVersion, promptSha256, model, result,
                    promptTokens, completionTokens, costMicros, Instant.now().toString());
        }
    }
}
