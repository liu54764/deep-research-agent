package com.dra.eval.judge;

import com.dra.common.hash.Hashing;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads judge prompts from {@code prompts/judge/*.v1.*.md} and renders {{vars}} in one pass. */
@Component
public class JudgePrompts {

    public static final String J1 = "j1.v1";
    public static final String J2 = "j2.v1";
    public static final String J3 = "j3.v1";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String system(String version) {
        return load("prompts/judge/" + version + ".system.md");
    }

    public String user(String version) {
        return load("prompts/judge/" + version + ".user.md");
    }

    public String render(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 512);
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Prompt fingerprint for cache keys and run manifests. */
    public String sha256(String version) {
        return Hashing.sha256Hex(system(version) + "\n---\n" + user(version));
    }

    private String load(String classpath) {
        return cache.computeIfAbsent(classpath, path -> {
            try (InputStream input = new ClassPathResource(path).getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot load judge prompt: " + path, e);
            }
        });
    }
}
