package com.dra.worker.activity;

import com.dra.contracts.dto.ConflictDraft;
import com.dra.contracts.dto.ConflictInput;
import com.dra.contracts.dto.ConflictReport;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.model.ReportConflict;
import com.dra.domain.port.CitationRepository;
import com.dra.domain.port.ClaimRepository;
import com.dra.domain.port.ConflictRepository;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictActivitiesImplTest {

    @Test
    void keepsOnlyValidatedClaimsAndUrls() {
        FakeConflicts conflicts = new FakeConflicts();
        FakeClaims claims = new FakeClaims();
        ConflictActivitiesImpl activity = new ConflictActivitiesImpl(
                new FakeLlm("""
                        {"conflicts":[
                          {"topic":"Long-running orchestration","summary":"来源意见不一致",
                           "positions":[
                             {"claimId":"claim-1","stance":"支持","urls":["https://a.test/1","https://bogus.test/x"]},
                             {"claimId":"claim-2","stance":"反对","urls":["https://b.test/2"]}
                           ]},
                          {"topic":"Invented","summary":"引用了不存在的结论",
                           "positions":[
                             {"claimId":"claim-9","stance":"支持","urls":[]},
                             {"claimId":"claim-1","stance":"反对","urls":[]}
                           ]}
                        ]}
                        """),
                claims, FakeCitations.of(Map.of(
                        "claim-1", List.of("ev-1"), "claim-2", List.of("ev-2"))),
                FakeEvidence.of(Map.of(
                        "ev-1", view("ev-1", "https://a.test/1"),
                        "ev-2", view("ev-2", "https://b.test/2"))),
                conflicts, new NoopUsage(), new PromptLoader(new DefaultResourceLoader()),
                properties(), new com.dra.worker.config.ConfidenceProperties(), new ObjectMapper());

        ConflictReport report = activity.detect(new ConflictInput("task-1", 1,
                List.of("claim-1", "claim-2", "claim-3")));

        assertEquals(1, report.conflicts().size());
        ConflictDraft draft = report.conflicts().get(0);
        assertEquals("Long-running orchestration", draft.topic());
        assertEquals(2, draft.positions().size());
        assertEquals(List.of("https://a.test/1"), draft.positions().get(0).urls());
        assertEquals(1, conflicts.saved.size());
        assertTrue(conflicts.saved.get(0).contains("claim-2"));
        // M9.5: conflicts now lower the claim confidence once per involved claim.
        assertEquals(0.7, claims.confidenceOf("claim-1"), 1e-9);
        assertEquals(0.5, claims.confidenceOf("claim-2"), 1e-9);
        assertEquals(0.1, claims.confidenceOf("claim-3"), 1e-9);
    }

    @Test
    void returnsEmptyWhenModelFails() {
        ConflictActivitiesImpl activity = new ConflictActivitiesImpl(
                new FakeLlm(null),
                new FakeClaims(),
                FakeCitations.of(Map.of("claim-1", List.of("ev-1"), "claim-2", List.of("ev-2"))),
                FakeEvidence.of(Map.of("ev-1", view("ev-1", "https://a.test/1"),
                        "ev-2", view("ev-2", "https://b.test/2"))),
                new FakeConflicts(), new NoopUsage(), new PromptLoader(new DefaultResourceLoader()),
                properties(), new com.dra.worker.config.ConfidenceProperties(), new ObjectMapper());

        ConflictReport report = activity.detect(new ConflictInput("task-1", 1,
                List.of("claim-1", "claim-2")));

        assertTrue(report.conflicts().isEmpty());
    }

    @Test
    void skipsWhenFewerThanTwoVerifiedClaims() {
        FakeConflicts conflicts = new FakeConflicts();
        ConflictActivitiesImpl activity = new ConflictActivitiesImpl(
                new FakeLlm("{\"conflicts\":[]}"),
                new FakeClaims(),
                FakeCitations.of(Map.of()),
                FakeEvidence.of(Map.of()),
                conflicts, new NoopUsage(), new PromptLoader(new DefaultResourceLoader()),
                properties(), new com.dra.worker.config.ConfidenceProperties(), new ObjectMapper());

        ConflictReport report = activity.detect(new ConflictInput("task-1", 1, List.of("claim-3")));

        assertTrue(report.conflicts().isEmpty());
        assertTrue(conflicts.saved.isEmpty());
    }

    private static EvidenceView view(String id, String url) {
        return new EvidenceView(id, "task-1", "src-1", "q1", 1,
                "A verbatim quote.", "FULL_TEXT", url, "Title");
    }

    private static LlmProperties properties() {
        LlmProperties properties = new LlmProperties();
        properties.setClaimModel("deepseek-chat");
        properties.setMaxTokens(2048);
        return properties;
    }

    private static final class FakeLlm implements LlmPort {
        private final String response;

        private FakeLlm(String response) {
            this.response = response;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            if (response == null) {
                throw new IllegalStateException("LLM unavailable");
            }
            return new LlmResponse(response, Usage.tokens(100, 50), request.model(), "stop");
        }
    }

    private static final class FakeClaims implements ClaimRepository {
        private final Map<String, ClaimRow> claims = new HashMap<>(Map.of(
                "claim-1", new ClaimRow("claim-1", "task-1", "s1", "Temporal suits long tasks", 4,
                        "SUPPORTED", 0.9),
                "claim-2", new ClaimRow("claim-2", "task-1", "s1", "Checkpointing is cheaper", 3,
                        "SUPPORTED", 0.7),
                "claim-3", new ClaimRow("claim-3", "task-1", "s1", "Unverified", 1,
                        "UNSUPPORTED", 0.1)));

        @Override
        public void save(ClaimRow claim) {
            claims.put(claim.id(), claim);
        }

        @Override
        public Optional<ClaimRow> findById(String claimId) {
            return Optional.ofNullable(claims.get(claimId));
        }

        @Override
        public void updateConfidence(String claimId, double confidence) {
            ClaimRow claim = claims.get(claimId);
            if (claim != null) {
                claims.put(claimId, new ClaimRow(claim.id(), claim.taskId(), claim.sectionKey(),
                        claim.statement(), claim.importance(), claim.status(), confidence));
            }
        }

        double confidenceOf(String claimId) {
            return claims.get(claimId).confidence();
        }
    }

    private static final class FakeCitations implements CitationRepository {
        private final Map<String, List<String>> byClaim;

        private FakeCitations(Map<String, List<String>> byClaim) {
            this.byClaim = byClaim;
        }

        static FakeCitations of(Map<String, List<String>> byClaim) {
            return new FakeCitations(byClaim);
        }

        @Override
        public void save(String citationId, String claimId, String evidenceId, String supportType) {
        }

        @Override
        public List<String> findEvidenceIds(String claimId) {
            return byClaim.getOrDefault(claimId, List.of());
        }
    }

    private static final class FakeEvidence implements EvidenceRepository {
        private final Map<String, EvidenceView> byId;

        private FakeEvidence(Map<String, EvidenceView> byId) {
            this.byId = byId;
        }

        static FakeEvidence of(Map<String, EvidenceView> byId) {
            return new FakeEvidence(byId);
        }

        @Override
        public void save(EvidenceRow evidence) {
        }

        @Override
        public List<EvidenceView> findByIds(String taskId, List<String> evidenceIds) {
            List<EvidenceView> views = new ArrayList<>();
            for (String id : evidenceIds) {
                EvidenceView view = byId.get(id);
                if (view != null) {
                    views.add(view);
                }
            }
            return views;
        }
    }

    private static final class FakeConflicts implements ConflictRepository {
        private final List<String> saved = new ArrayList<>();

        @Override
        public void save(String conflictId, String taskId, String groupKey, String summary,
                         String positionsJson) {
            saved.add(conflictId + "|" + groupKey + "|" + positionsJson);
        }

        @Override
        public List<ReportConflict> findByTask(String taskId) {
            return List.of();
        }
    }

    private static final class NoopUsage implements UsageRepository {
        @Override
        public void record(String taskId, String activity, String profile, Usage usage) {
        }
    }
}
