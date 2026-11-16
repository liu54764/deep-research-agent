package com.dra.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CLI options for the load generator. No framework: a few flags, strict validation. */
public final class BenchOptions {

    private String apiUrl = "http://localhost:8080";
    private String apiKey = "dev-key";
    private int tasks = 10;
    private int concurrency = 4;
    private String depth = "QUICK";
    private long pollIntervalMs = 1000;
    private int timeoutMinutes = 30;
    private int rampSeconds;
    private String questionsPath = "";
    private String out = "perf/runs";
    private double minSuccessRate = 0.98;
    private double maxP95Seconds = 300;
    private boolean help;

    public static BenchOptions parse(String[] args) {
        BenchOptions options = new BenchOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.help = true;
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String key;
            String value;
            int eq = arg.indexOf('=');
            if (eq > 2) {
                key = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                key = arg.substring(2);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for --" + key);
                }
                value = args[++i];
            }
            options.apply(key, value);
        }
        options.validate();
        return options;
    }

    private void apply(String key, String value) {
        switch (key) {
            case "api-url" -> apiUrl = value;
            case "api-key" -> apiKey = value;
            case "tasks" -> tasks = intValue(key, value);
            case "concurrency" -> concurrency = intValue(key, value);
            case "depth" -> depth = value.toUpperCase(Locale.ROOT);
            case "poll-interval-ms" -> pollIntervalMs = longValue(key, value);
            case "timeout-minutes" -> timeoutMinutes = intValue(key, value);
            case "ramp-seconds" -> rampSeconds = intValue(key, value);
            case "questions" -> questionsPath = value;
            case "out" -> out = value;
            case "min-success-rate" -> minSuccessRate = doubleValue(key, value);
            case "max-p95-seconds" -> maxP95Seconds = doubleValue(key, value);
            default -> throw new IllegalArgumentException("unknown option --" + key);
        }
    }

    private void validate() {
        if (help) {
            return;
        }
        if (tasks <= 0) {
            throw new IllegalArgumentException("--tasks must be > 0");
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("--concurrency must be > 0");
        }
        if (!List.of("QUICK", "STANDARD", "DEEP").contains(depth)) {
            throw new IllegalArgumentException("--depth must be QUICK, STANDARD or DEEP");
        }
        if (pollIntervalMs < 100) {
            throw new IllegalArgumentException("--poll-interval-ms must be >= 100");
        }
        if (timeoutMinutes <= 0) {
            throw new IllegalArgumentException("--timeout-minutes must be > 0");
        }
        if (rampSeconds < 0) {
            throw new IllegalArgumentException("--ramp-seconds must be >= 0");
        }
        if (minSuccessRate < 0 || minSuccessRate > 1) {
            throw new IllegalArgumentException("--min-success-rate must be within [0,1]");
        }
        if (maxP95Seconds < 0) {
            throw new IllegalArgumentException("--max-p95-seconds must be >= 0 (0 disables)");
        }
    }

    private static int intValue(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " expects an integer, got '" + value + "'");
        }
    }

    private static long longValue(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " expects a number, got '" + value + "'");
        }
    }

    private static double doubleValue(String key, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " expects a number, got '" + value + "'");
        }
    }

    /** Default question pool used when {@code --questions} is not given. */
    public static List<String> defaultQuestions() {
        return new ArrayList<>(List.of(
                "Temporal 和 LangGraph 在长任务编排上各自适合什么场景？",
                "MySQL InnoDB 的默认事务隔离级别是什么？它如何避免不可重复读和幻读？",
                "Redis 的 RDB 和 AOF 在持久性与性能上如何取舍？",
                "Java 21 的虚拟线程能直接替代现有线程池吗？有哪些使用限制？",
                "抓取搜索结果里的 URL 时，SSRF 防护应该怎么做？只校验初始 URL 是否足够？",
                "Kafka 的 exactly-once 语义在什么范围内成立？要做到端到端 exactly-once 还需要什么？"
        ));
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getTasks() {
        return tasks;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public String getDepth() {
        return depth;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public int getRampSeconds() {
        return rampSeconds;
    }

    public String getQuestionsPath() {
        return questionsPath;
    }

    public String getOut() {
        return out;
    }

    public double getMinSuccessRate() {
        return minSuccessRate;
    }

    public double getMaxP95Seconds() {
        return maxP95Seconds;
    }

    public boolean isHelp() {
        return help;
    }

    public static String usage() {
        return """
                Usage: java -jar dra-bench.jar [options]

                  --api-url=<url>             API base URL (default http://localhost:8080)
                  --api-key=<key>             X-API-Key (default dev-key)
                  --tasks=<n>                 total tasks to submit (default 10)
                  --concurrency=<n>           parallel in-flight tasks (default 4)
                  --depth=QUICK|STANDARD|DEEP task depth (default QUICK)
                  --poll-interval-ms=<ms>     status polling interval (default 1000)
                  --timeout-minutes=<n>       per-task timeout (default 30)
                  --ramp-seconds=<n>          spread submissions over n seconds (default 0)
                  --questions=<file>          one question per line (default built-in pool)
                  --out=<dir>                 artifact directory (default perf/runs)
                  --min-success-rate=<0..1>   gate (default 0.98)
                  --max-p95-seconds=<n>       gate; 0 disables (default 300)
                  --help                      print this message

                Exit codes: 0 pass, 1 gate failed, 3 infra failure (API unreachable).
                """;
    }
}
