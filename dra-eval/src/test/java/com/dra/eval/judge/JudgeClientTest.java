package com.dra.eval.judge;

import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmResponse;
import com.dra.eval.config.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeClientTest {

    private static final String VALID = "{\"verdict\":\"SUPPORT\",\"citations\":[]}";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JudgePrompts prompts = new JudgePrompts();
    @TempDir
    Path dir;

    @Test
    void secondIdenticalRequestHitsTheCache() {
        AtomicInteger calls = new AtomicInteger();
        JudgeClient client = client(request -> {
            calls.incrementAndGet();
            return response(VALID);
        });

        JsonNode first = client.askJson("q1", "j1", JudgePrompts.J1,
                prompts.system(JudgePrompts.J1), "user").result();
        JsonNode second = client.askJson("q1", "j1", JudgePrompts.J1,
                prompts.system(JudgePrompts.J1), "user").result();

        assertEquals(1, calls.get());
        assertEquals("SUPPORT", first.path("verdict").asText());
        assertEquals("SUPPORT", second.path("verdict").asText());
        assertEquals(1, client.counters().cacheHits());
        assertEquals(1, client.counters().calls());
        assertEquals(7, client.counters().costMicros());
    }

    @Test
    void invalidOutputIsRepairedWithOneExtraCall() {
        AtomicInteger calls = new AtomicInteger();
        JudgeClient client = client(request -> {
            int call = calls.incrementAndGet();
            return response(call == 1 ? "sorry, no json here" : VALID);
        });

        JsonNode node = client.askJson("q1", "j1", JudgePrompts.J1, "sys", "user").result();

        assertNotNull(node);
        assertEquals(2, calls.get());
        assertEquals(0, client.counters().invalidOutputs());
    }

    @Test
    void invalidOutputTwiceCountsAsInvalidAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        JudgeClient client = client(request -> {
            calls.incrementAndGet();
            return response("still not json");
        });

        assertThrows(JudgeClient.JudgeInvalidException.class,
                () -> client.askJson("q1", "j1", JudgePrompts.J1, "sys", "user"));
        assertEquals(2, calls.get());
        assertEquals(1, client.counters().invalidOutputs());

        // A later valid answer must not be shadowed by a bad cache entry.
        JudgeClient retry = client(request -> response(VALID));
        assertNotNull(retry.askJson("q1", "j1", JudgePrompts.J1, "sys", "user"));
    }

    @Test
    void extractJsonStripsCodeFencesAndProse() {
        JsonNode node = JudgeClient.extractJson("好的：\n```json\n{\"a\":1}\n```\n希望有帮助", mapper);

        assertNotNull(node);
        assertEquals(1, node.path("a").asInt());
    }

    @Test
    void extractJsonRejectsNonJson() {
        assertEquals(null, JudgeClient.extractJson("no braces here", mapper));
        assertEquals(null, JudgeClient.extractJson("", mapper));
    }

    private JudgeClient client(LlmPort port) {
        EvalProperties properties = new EvalProperties();
        properties.setCacheDir(dir.toString());
        properties.getJudge().setModel("judge-test");
        return new JudgeClient(properties, mapper, new JudgeCache(mapper, properties), prompts, port);
    }

    private static LlmResponse response(String content) {
        return new LlmResponse(content, new Usage(20, 5, 7), "judge-test", "stop");
    }
}
