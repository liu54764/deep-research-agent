package com.dra.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluation CLI. Non-web Spring Boot app: submits tasks over the public REST API
 * (black box) and reads claim/citation detail from MySQL (white box).
 *
 * <p>Only eval + persistence + Jackson config are scanned on purpose: the CLI must not
 * drag in Temporal/Redis/LLM beans it does not need.
 */
@SpringBootApplication(scanBasePackages = {
        "com.dra.eval",
        "com.dra.infra.persistence",
        "com.dra.infra.config"
})
@ConfigurationPropertiesScan("com.dra")
public class EvalApplication {

    /** Short flags from the docs mapped onto their relaxed-binding property names. */
    private static final Map<String, String> FLAGS = Map.ofEntries(
            Map.entry("mode", "dra.eval.mode"),
            Map.entry("api-base-url", "dra.eval.api-base-url"),
            Map.entry("api-key", "dra.eval.api-key"),
            Map.entry("dataset", "dra.eval.dataset"),
            Map.entry("split", "dra.eval.split"),
            Map.entry("depth", "dra.eval.depth"),
            Map.entry("limit", "dra.eval.limit"),
            Map.entry("parallel", "dra.eval.parallel"),
            Map.entry("out", "dra.eval.out"),
            Map.entry("baseline", "dra.eval.baseline"),
            Map.entry("freeze-baseline", "dra.eval.freeze-baseline"),
            Map.entry("rescore-from", "dra.eval.rescore-from"),
            Map.entry("thresholds-enabled", "dra.eval.thresholds-enabled"),
            Map.entry("min-car", "dra.eval.thresholds.min-car"),
            Map.entry("min-hfr", "dra.eval.thresholds.min-hfr"),
            Map.entry("min-gcr", "dra.eval.thresholds.min-gcr"),
            Map.entry("max-contradiction-rate", "dra.eval.thresholds.max-contradiction-rate"),
            Map.entry("min-success-rate", "dra.eval.thresholds.min-success-rate"),
            Map.entry("confidence-min-samples", "dra.eval.confidence-min-samples"),
            Map.entry("confidence-max-ece", "dra.eval.confidence-max-ece"),
            Map.entry("max-cost-cny", "dra.eval.max-cost-cny"),
            Map.entry("poll-interval-ms", "dra.eval.poll-interval-ms"),
            Map.entry("task-timeout-minutes", "dra.eval.task-timeout-minutes"),
            Map.entry("judge-enabled", "dra.eval.judge.enabled"),
            Map.entry("judge-model", "dra.eval.judge.model"),
            Map.entry("judge-base-url", "dra.eval.judge.base-url"),
            Map.entry("judge-api-key", "dra.eval.judge.api-key"),
            Map.entry("judge-temperature", "dra.eval.judge.temperature"),
            Map.entry("judge-max-tokens", "dra.eval.judge.max-tokens"),
            Map.entry("labels", "dra.eval.labels"),
            Map.entry("labels-dir", "dra.eval.labels-dir"),
            Map.entry("cache-dir", "dra.eval.cache-dir"),
            Map.entry("kappa-threshold", "dra.eval.kappa-threshold"),
            Map.entry("kappa-sample-j1", "dra.eval.kappa-sample-j1"),
            Map.entry("kappa-sample-j2", "dra.eval.kappa-sample-j2"),
            Map.entry("url-check", "dra.eval.url-check.enabled"));

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                SpringApplication.run(EvalApplication.class, normalize(args));
        EvalRunner runner = context.getBean(EvalRunner.class);
        System.exit(SpringApplication.exit(context, runner));
    }

    /**
     * Rewrites {@code --mode=real} into {@code --dra.eval.mode=real} so the CLI reads
     * naturally while still binding through {@code @ConfigurationProperties}.
     */
    static String[] normalize(String[] args) {
        List<String> normalized = new ArrayList<>(args.length);
        for (String arg : args) {
            if (arg.startsWith("--") && arg.indexOf('=') > 2) {
                String key = arg.substring(2, arg.indexOf('='));
                String value = arg.substring(arg.indexOf('=') + 1);
                String mapped = FLAGS.get(key);
                if (mapped != null) {
                    normalized.add("--" + mapped + "=" + value);
                    continue;
                }
                if (key.startsWith("judge.") || key.startsWith("url-check.")) {
                    normalized.add("--dra.eval." + key + "=" + value);
                    continue;
                }
            }
            normalized.add(arg);
        }
        return normalized.toArray(String[]::new);
    }
}
