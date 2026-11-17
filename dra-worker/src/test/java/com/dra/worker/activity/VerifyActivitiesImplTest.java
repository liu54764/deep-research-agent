package com.dra.worker.activity;

import com.dra.contracts.dto.Usage;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.model.SourceContent;
import com.dra.domain.model.VerificationRow;
import com.dra.domain.port.CitationRepository;
import com.dra.domain.port.ClaimRepository;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.domain.port.VerificationRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyActivitiesImplTest {

    private static final String QUOTE = "Durable execution recovers workflow state after crashes.";

    @Test
    void rulePassPlusSupportBecomesSupported() {
        Fixture fixture = new Fixture(QUOTE, """
                {"verdict":"SUPPORT","confidence":0.9,"rationale":"evidence supports it"}
                """);
        Verification result = fixture.activity.verify(new VerifyInput("t1", "c1", 1));

        assertEquals("PASS", result.ruleVerdict());
        assertEquals("SUPPORTED", result.finalVerdict());
        assertEquals("SUPPORTED", fixture.claims.saved.get(0).status());
        assertEquals(1, fixture.verifications.saved.size());
        assertTrue(result.confidence() > 0.5);

        // Components are persisted so offline calibration can explain the score.
        VerificationRow saved = fixture.verifications.saved.get(0);
        assertEquals(0.9, saved.judgeConfidence(), 1e-9);
        assertEquals(1.0, saved.ruleRatio(), 1e-9);
        assertEquals(1.0 / 3.0, saved.diversityScore(), 1e-9);
        assertEquals(0.5 * 0.9 + 0.3 * 1.0 + 0.2 * (1.0 / 3.0), saved.confidence(), 1e-9);
    }

    @Test
    void ruleFailureIsUnsupportedEvenIfJudgeSaysSupport() {
        Fixture fixture = new Fixture("completely different content with no matching quote",
                """
                        {"verdict":"SUPPORT","confidence":0.99,"rationale":"judge is overconfident"}
                        """);
        Verification result = fixture.activity.verify(new VerifyInput("t1", "c1", 1));

        assertEquals("FAIL", result.ruleVerdict());
        assertEquals("UNSUPPORTED", result.finalVerdict());
        assertTrue(result.confidence() < 0.2);
        assertEquals(0, fixture.llm.calls, "judge must not run when the rule channel fails");
    }

    @Test
    void judgeRefuteBecomesRefuted() {
        Fixture fixture = new Fixture(QUOTE, """
                {"verdict":"REFUTE","confidence":0.9,"rationale":"contradiction"}
                """);
        Verification result = fixture.activity.verify(new VerifyInput("t1", "c1", 1));

        assertEquals("REFUTED", result.finalVerdict());
        assertTrue(result.confidence() <= 0.3);
    }

    private static final class Fixture {
        private final FakeClaims claims;
        private final FakeVerifications verifications;
        private final FakeLlm llm;
        private final VerifyActivitiesImpl activity;

        private Fixture(String content, String llmResponse) {
            ClaimRow claim = new ClaimRow("c1", "t1", "s1",
                    "Durable execution recovers state.", 4, "UNVERIFIED", 0);
            claims = new FakeClaims(claim);
            verifications = new FakeVerifications();
            FakeEvidence evidence = new FakeEvidence(new EvidenceView(
                    "e1", "t1", "s1", "q1", 1, QUOTE, "FULL_TEXT",
                    "https://example.org/a", "A"));
            llm = new FakeLlm(llmResponse);
            activity = new VerifyActivitiesImpl(
                    llm, claims, evidence, new FakeCitations(List.of("e1")),
                    verifications, new FakeSources(content), new NoopUsage(),
                    new PromptLoader(new DefaultResourceLoader()), properties(),
                    new com.dra.worker.config.ConfidenceProperties(), new ObjectMapper());
        }
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setVerifyModel("deepseek-reasoner");
        properties.setMaxTokens(2048);
        return properties;
    }

    private static final class FakeLlm implements LlmPort {
        private final Deque<String> responses = new ArrayDeque<>();
        private int calls;

        private FakeLlm(String response) {
            responses.add(response);
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            String response = responses.isEmpty() ? "not json" : responses.poll();
            return new LlmResponse(response, Usage.tokens(100, 50), request.model(), "stop");
        }
    }

    private static final class FakeClaims implements ClaimRepository {
        private final ClaimRow claim;
        private final List<ClaimRow> saved = new ArrayList<>();

        private FakeClaims(ClaimRow claim) {
            this.claim = claim;
        }

        @Override
        public void save(ClaimRow claim) {
            saved.add(claim);
        }

        @Override
        public Optional<ClaimRow> findById(String claimId) {
            return Optional.of(claim);
        }

        @Override
        public void updateConfidence(String claimId, double confidence) {
        }
    }

    private static final class FakeEvidence implements EvidenceRepository {
        private final EvidenceView view;

        private FakeEvidence(EvidenceView view) {
            this.view = view;
        }

        @Override
        public void save(EvidenceRow evidence) {
        }

        @Override
        public List<EvidenceView> findByIds(String taskId, List<String> evidenceIds) {
            return List.of(view);
        }
    }

    private static final class FakeCitations implements CitationRepository {
        private final List<String> evidenceIds;

        private FakeCitations(List<String> evidenceIds) {
            this.evidenceIds = evidenceIds;
        }

        @Override
        public void save(String citationId, String claimId, String evidenceId, String supportType) {
        }

        @Override
        public List<String> findEvidenceIds(String claimId) {
            return evidenceIds;
        }
    }

    private static final class FakeSources implements SourceRepository {
        private final String content;

        private FakeSources(String content) {
            this.content = content;
        }

        @Override
        public void save(com.dra.domain.model.FetchedSource source) {
        }

        @Override
        public Optional<SourceContent> findContent(String sourceId) {
            return Optional.of(new SourceContent(sourceId, "https://example.org/a", "A", content));
        }
    }

    private static final class FakeVerifications implements VerificationRepository {
        private final List<VerificationRow> saved = new ArrayList<>();

        @Override
        public void save(VerificationRow verification) {
            saved.add(verification);
        }
    }

    private static final class NoopUsage implements UsageRepository {
        @Override
        public void record(String taskId, String activity, String profile, Usage usage) {
        }
    }
}
