package com.dra.worker.activity;

import com.dra.contracts.dto.ExtractInput;
import com.dra.contracts.dto.ExtractResult;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.model.FetchedSource;
import com.dra.domain.model.SourceContent;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractActivitiesImplTest {

    private static final String CONTENT =
            "Mock content for https://example.org/a. Durable execution recovers workflow state after crashes. "
                    + "LangGraph checkpoints persist graph state.";

    @Test
    void dropsHallucinatedQuotesAndPersistsVerbatimOnes() {
        FakeEvidence evidence = new FakeEvidence();
        ExtractActivitiesImpl activity = new ExtractActivitiesImpl(
                new FakeLlm("""
                        {"facts":[
                          {"subQuestionId":"q1","quote":"Durable execution recovers workflow state after crashes.","aspect":"definition"},
                          {"subQuestionId":"q1","quote":"This sentence never appears in the source.","aspect":"fake"}
                        ]}
                        """),
                new FakeSource(CONTENT), evidence, new NoopUsage(),
                new PromptLoader(new DefaultResourceLoader()), properties(), new ObjectMapper());

        ExtractResult result = activity.extract(input());

        assertEquals(1, result.evidenceIds().size());
        assertEquals(1, evidence.saved.size());
        assertTrue(CONTENT.contains(evidence.saved.get(0).quote()));
        assertEquals(26, evidence.saved.get(0).id().length());
    }

    @Test
    void repairsWrappedQuoteToTheExactSourceSpanAndStoresAnchor() {
        FakeEvidence evidence = new FakeEvidence();
        ExtractActivitiesImpl activity = new ExtractActivitiesImpl(
                new FakeLlm("""
                        {"facts":[
                          {"subQuestionId":"q1","quote":"“Durable execution recovers workflow state after crashes.”","aspect":"definition"}
                        ]}
                        """),
                new FakeSource(CONTENT), evidence, new NoopUsage(),
                new PromptLoader(new DefaultResourceLoader()), properties(), new ObjectMapper());

        ExtractResult result = activity.extract(input());

        assertEquals(1, result.evidenceIds().size());
        EvidenceRow saved = evidence.saved.get(0);
        assertEquals("Durable execution recovers workflow state after crashes.", saved.quote());
        assertTrue(CONTENT.contains(saved.quote()));
        assertEquals("definition", saved.aspect());
        int start = CONTENT.indexOf(saved.quote());
        assertEquals("{\"start\":" + start + ",\"end\":" + (start + saved.quote().length()) + "}",
                saved.anchorJson());
    }

    @Test
    void returnsNoEvidenceWhenJsonIsInvalidTwice() {
        FakeEvidence evidence = new FakeEvidence();
        ExtractActivitiesImpl activity = new ExtractActivitiesImpl(
                new FakeLlm("not json", "still not json"),
                new FakeSource(CONTENT), evidence, new NoopUsage(),
                new PromptLoader(new DefaultResourceLoader()), properties(), new ObjectMapper());

        ExtractResult result = activity.extract(input());

        assertTrue(result.evidenceIds().isEmpty());
        assertTrue(evidence.saved.isEmpty());
    }

    private static ExtractInput input() {
        return new ExtractInput("task-1", "source-1",
                List.of(new SubQuestion("q1", 1, "Approach", "What is the approach?",
                        List.of("query"), "OPEN")), 1);
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setExtractModel("deepseek-chat");
        properties.setMaxTokens(2048);
        return properties;
    }

    private static final class FakeLlm implements LlmPort {
        private final List<String> responses = new ArrayList<>();

        private FakeLlm(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            String response = responses.isEmpty() ? "not json" : responses.remove(0);
            return new LlmResponse(response, Usage.tokens(100, 50), request.model(), "stop");
        }
    }

    private static final class FakeSource implements SourceRepository {
        private final String content;

        private FakeSource(String content) {
            this.content = content;
        }

        @Override
        public void save(FetchedSource source) {
        }

        @Override
        public Optional<SourceContent> findContent(String sourceId) {
            return Optional.of(new SourceContent(sourceId, "https://example.org/a", "A", content));
        }
    }

    private static final class FakeEvidence implements EvidenceRepository {
        private final List<EvidenceRow> saved = new ArrayList<>();

        @Override
        public void save(EvidenceRow evidence) {
            saved.add(evidence);
        }

        @Override
        public List<EvidenceView> findByIds(String taskId, List<String> evidenceIds) {
            return List.of();
        }
    }

    private static final class NoopUsage implements UsageRepository {
        @Override
        public void record(String taskId, String activity, String profile, Usage usage) {
        }
    }
}
