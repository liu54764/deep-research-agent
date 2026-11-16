package com.dra.worker.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String classpath) {
        return cache.computeIfAbsent(classpath, path -> {
            Resource resource = resourceLoader.getResource("classpath:" + path);
            try (InputStream input = resource.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("cannot load prompt: " + path, e);
            }
        });
    }

    public String render(String template, Map<String, String> variables) {
        // Single pass: values are never re-scanned for placeholders (source content
        // may legitimately contain "{{...}}").
        Pattern pattern = Pattern.compile("\\{\\{(\\w+)}}");
        Matcher matcher = pattern.matcher(template);
        StringBuilder result = new StringBuilder(template.length() + 256);
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
