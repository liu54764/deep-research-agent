package com.dra.eval.judge;

import com.dra.common.hash.Hashing;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.eval.config.EvalProperties;
import com.dra.eval.support.EvalInfraException;
import com.dra.infra.llm.LlmProperties;
import com.dra.infra.llm.SpringAiLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin judge wrapper: independent model endpoint, strict-JSON parsing with one repair
 * retry, and a content-addressed cache so {@code rescore} and prompt experiments do not
 * pay twice for identical requests.
 */
@Component
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);
    private static final String REPAIR_SUFFIX =
            "\n\n上一次输出不是合法 JSON。请只输出符合要求的严格 JSON 对象，不要解释、不要代码块。";

    private final EvalProperties properties;
    private final ObjectMapper objectMapper;
    private final JudgeCache cache;
    private final JudgePrompts prompts;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger invalidOutputs = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong costMicros = new AtomicLong();

    private volatile LlmPort llm;

    @Autowired
    public JudgeClient(EvalProperties properties, ObjectMapper objectMapper,
                       JudgeCache cache, JudgePrompts prompts) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.cache = cache;
        this.prompts = prompts;
    }

    /** Test seam: inject a fake model instead of building a Spring AI client. */
    JudgeClient(EvalProperties properties, ObjectMapper objectMapper, JudgeCache cache,
                JudgePrompts prompts, LlmPort llm) {
        this(properties, objectMapper, cache, prompts);
        this.llm = llm;
    }

    public Judged askJson(String taskId, String kind, String version,
                          String system, String user) {
        String key = Hashing.sha256Hex(version + "|" + system + "|" + user);
        Optional<JudgeCache.Entry> cached = cache.get(key);
        if (cached.isPresent()) {
            cacheHits.incrementAndGet();
            return new Judged(cached.get().result(), 0, 0, 0, true);
        }

        Call call = call(taskId, kind, system, user);
        JsonNode node = extractJson(call.content());
        if (node == null) {
            Call repair = call(taskId, kind, system, user + REPAIR_SUFFIX);
            call = call.plus(repair);
            node = extractJson(repair.content());
        }
        if (node == null) {
            invalidOutputs.incrementAndGet();
            // Without the raw tail an unparseable verdict is undiagnosable after the fact:
            // a truncated response (reasoning tokens eating max-tokens) looks identical to
            // a model that simply ignored the schema.
            log.warn("judge {} returned no parseable JSON after repair (chars={}, completionTokens={}), tail: {}",
                    kind, length(call.content()), call.completionTokens(), tail(call.content(), 300));
            throw new JudgeInvalidException(kind + " returned no parseable JSON");
        }
        cache.put(key, JudgeCache.Entry.of(version, prompts.sha256(version),
                properties.getJudge().getModel(), node,
                call.promptTokens(), call.completionTokens(), call.costMicros()));
        return new Judged(node, call.promptTokens(), call.completionTokens(),
                call.costMicros(), false);
    }

    public Counters counters() {
        return new Counters(calls.get(), cacheHits.get(), invalidOutputs.get(), failures.get(),
                promptTokens.get(), completionTokens.get(), costMicros.get());
    }

    /** Tests use this; a CLI process runs one eval and exits. */
    public void resetCounters() {
        calls.set(0);
        cacheHits.set(0);
        invalidOutputs.set(0);
        failures.set(0);
        promptTokens.set(0);
        completionTokens.set(0);
        costMicros.set(0);
    }

    private Call call(String taskId, String kind, String system, String user) {
        LlmRequest request = new LlmRequest(taskId, "judge." + kind,
                properties.getJudge().getModel(), system, user,
                properties.getJudge().getTemperature(), properties.getJudge().getMaxTokens());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                LlmResponse response = llm().complete(request);
                calls.incrementAndGet();
                Usage usage = response.usage();
                promptTokens.addAndGet(usage.promptTokens());
                completionTokens.addAndGet(usage.completionTokens());
                costMicros.addAndGet(usage.costMicros());
                return new Call(response.content(), usage.promptTokens(),
                        usage.completionTokens(), usage.costMicros());
            } catch (RuntimeException e) {
                last = e;
                failures.incrementAndGet();
                log.warn("judge call failed (attempt {}): {}", attempt, e.getMessage());
                if (attempt == 1) {
                    sleep(400);
                }
            }
        }
        throw new EvalInfraException("judge endpoint unavailable: "
                + (last == null ? "unknown" : last.getMessage()), last);
    }

    private LlmPort llm() {
        LlmPort local = llm;
        if (local == null) {
            synchronized (this) {
                local = llm;
                if (local == null) {
                    local = build();
                    llm = local;
                }
            }
        }
        return local;
    }

    private LlmPort build() {
        EvalProperties.Judge judge = properties.getJudge();
        if (judge.getApiKey() == null || judge.getApiKey().isBlank()) {
            throw new EvalInfraException(
                    "judge is enabled but no API key: set DRA_EVAL_JUDGE_API_KEY or --judge.api-key");
        }
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setEnabled(true);
        llmProperties.setBaseUrl(judge.getBaseUrl());
        llmProperties.setApiKey(judge.getApiKey());
        llmProperties.setPlanModel(judge.getModel());
        llmProperties.setPlanTemperature(judge.getTemperature());
        llmProperties.setMaxTokens(judge.getMaxTokens());
        llmProperties.setConnectTimeoutSeconds(judge.getConnectTimeoutSeconds());
        llmProperties.setReadTimeoutSeconds(judge.getReadTimeoutSeconds());
        llmProperties.setInputMicrosPerMillionTokens(judge.getInputMicrosPerMillionTokens());
        llmProperties.setOutputMicrosPerMillionTokens(judge.getOutputMicrosPerMillionTokens());
        log.info("judge model={} baseUrl={}", judge.getModel(), judge.getBaseUrl());
        return new SpringAiLlmClient(llmProperties);
    }

    /** Strips code fences and prose around the first JSON object. */
    static JsonNode extractJson(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode extractJson(String raw) {
        return extractJson(raw, objectMapper);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    /** Last {@code max} chars: truncation always breaks the end of the JSON, not the start. */
    private static String tail(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : "…" + value.substring(value.length() - max);
    }

    private record Call(String content, long promptTokens, long completionTokens, long costMicros) {
        Call plus(Call other) {
            return new Call(other.content, promptTokens + other.promptTokens,
                    completionTokens + other.completionTokens, costMicros + other.costMicros);
        }
    }

    public record Counters(int calls, int cacheHits, int invalidOutputs, int failures,
                           long promptTokens, long completionTokens, long costMicros) {
    }

    /**
     * Result plus this call's own usage. Per-question metrics must use these values, not
     * deltas of the shared counters: concurrent questions would over-count.
     */
    public record Judged(JsonNode result, long promptTokens, long completionTokens,
                         long costMicros, boolean cacheHit) {
    }

    public static class JudgeInvalidException extends RuntimeException {
        public JudgeInvalidException(String message) {
            super(message);
        }
    }
}
