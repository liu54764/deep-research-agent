package com.dra.eval.judge;

import com.dra.contracts.dto.ReportDocument;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmResponse;
import com.dra.eval.config.EvalProperties;
import com.dra.eval.dataset.EvalQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeScorerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JudgePrompts prompts = new JudgePrompts();

    @TempDir
    Path dir;

    @Test
    void computesCitationCoverageAndCorrectnessMetrics() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("""
                        {"citations":[{"ref":"[1]","verdict":"SUPPORT"},
                                      {"ref":"[2]","verdict":"REFUTE"}],"rationale":"x"}
                        """);
            }
            if (system.contains("覆盖度")) {
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true},"
                        + "{\"kpId\":\"kp2\",\"covered\":false}]}");
            }
            if (system.contains("事实对错")) {
                return response("{\"claims\":[{\"claimId\":\"c1\",\"verdict\":\"SUPPORTED\"}]}");
            }
            throw new IllegalStateException("unexpected judge prompt");
        });

        JudgeResult result = scorer.score(question(), document());

        assertEquals(2, result.citationsJudged());
        assertEquals(1, result.support());
        assertEquals(1, result.refute());
        assertEquals(0.5, result.carStrict(), 1e-9);
        assertEquals(0.5, result.carWeighted(), 1e-9);
        assertEquals(1, result.claimsWithCitations());
        assertEquals(1, result.claimsWithSupport());
        assertEquals(1, result.claimsWithInvalid());
        assertEquals(0.0, result.hfr(), 1e-9);
        assertEquals(1.0, result.csr(), 1e-9);

        assertEquals(2, result.keyPointsJudged());
        assertEquals(1.0, result.coveredWeight(), 1e-9);
        assertEquals(1.6, result.totalWeight(), 1e-9);
        assertEquals(0.625, result.gcr(), 1e-9);

        assertEquals(1, result.exactClaimsJudged());
        assertEquals(1.0, result.claimPrecision(), 1e-9);
        assertEquals(0.0, result.contradictionRate(), 1e-9);

        assertEquals(1, result.j1Calls());
        assertEquals(1, result.j2Calls());
        assertEquals(1, result.j3Calls());
        assertEquals(21, result.costMicros());
        assertEquals(2, result.j1Candidates().size());
        assertEquals(2, result.j2Candidates().size());

        // Calibration ground truth prefers J3 on exact questions.
        assertEquals(1, result.claimSamples().size());
        assertEquals("c1", result.claimSamples().get(0).claimId());
        assertEquals("J3", result.claimSamples().get(0).source());
        assertEquals(true, result.claimSamples().get(0).correct());
        assertEquals(0.8, result.claimSamples().get(0).confidence(), 1e-9);
    }

    @Test
    void keyPointQuestionsFallBackToCitationSupportForSamples() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("{\"citations\":[{\"ref\":\"[1]\",\"verdict\":\"SUPPORT\"},"
                        + "{\"ref\":\"[2]\",\"verdict\":\"REFUTE\"}]}");
            }
            if (system.contains("覆盖度")) {
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true},"
                        + "{\"kpId\":\"kp2\",\"covered\":false}]}");
            }
            throw new IllegalStateException("J3 must not run for keypoints questions");
        });

        JudgeResult result = scorer.score(keyPointsQuestion(), document());

        assertEquals(0, result.j3Calls());
        assertEquals(1, result.claimSamples().size());
        assertEquals("J1", result.claimSamples().get(0).source());
        assertEquals(false, result.claimSamples().get(0).correct(),
                "a REFUTED citation makes the claim incorrect");
    }

    @Test
    void claimsWithoutCitationsProduceNoSamples() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("覆盖度")) {
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true},"
                        + "{\"kpId\":\"kp2\",\"covered\":true}]}");
            }
            throw new IllegalStateException("no citation judge expected");
        });

        JudgeResult result = scorer.score(keyPointsQuestion(), documentWithoutCitations());

        assertTrue(result.claimSamples().isEmpty());
    }

    @Test
    void missingKeyPointVerdictIsCountedButExcludedFromCoverage() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("{\"citations\":[{\"ref\":\"[1]\",\"verdict\":\"SUPPORT\"}]}");
            }
            if (system.contains("覆盖度")) {
                // kp2 omitted on purpose.
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true}]}");
            }
            return response("{\"claims\":[{\"claimId\":\"c1\",\"verdict\":\"SUPPORTED\"}]}");
        });

        JudgeResult result = scorer.score(question(), document());

        assertEquals(1, result.keyPointsJudged());
        assertEquals(1.0, result.gcr(), 1e-9);
        assertTrue(result.invalidOutputs() >= 1);
    }

    @Test
    void cachedJudgementsCostNothingAndCountAsHits() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("{\"citations\":[{\"ref\":\"[1]\",\"verdict\":\"SUPPORT\"}]}");
            }
            if (system.contains("覆盖度")) {
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true},"
                        + "{\"kpId\":\"kp2\",\"covered\":true}]}");
            }
            return response("{\"claims\":[{\"claimId\":\"c1\",\"verdict\":\"SUPPORTED\"}]}");
        });

        JudgeResult first = scorer.score(question(), document());
        JudgeResult second = scorer.score(question(), document());

        assertEquals(0, second.costMicros());
        assertEquals(0, second.promptTokens());
        assertEquals(3, second.cacheHits(), "J1 + J2 + J3 all hit the cache");
        assertTrue(first.costMicros() > 0);
    }

    @Test
    void partialVerdictCountsHalfForWeightedCar() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("{\"citations\":[{\"ref\":\"[1]\",\"verdict\":\"PARTIAL\"},"
                        + "{\"ref\":\"[2]\",\"verdict\":\"UNRELATED\"}]}");
            }
            if (system.contains("覆盖度")) {
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":true},"
                        + "{\"kpId\":\"kp2\",\"covered\":true}]}");
            }
            return response("{\"claims\":[{\"claimId\":\"c1\",\"verdict\":\"CONTRADICTED\"}]}");
        });

        JudgeResult result = scorer.score(question(), document());

        assertEquals(0.0, result.carStrict(), 1e-9);
        assertEquals(0.25, result.carWeighted(), 1e-9);
        assertEquals(1.0, result.gcr(), 1e-9);
        assertEquals(1.0, result.contradictionRate(), 1e-9);
        assertEquals(0.0, result.claimPrecision(), 1e-9);
    }

    /**
     * A reasoning judge that receives every claim at once spends its whole output budget
     * on hidden reasoning and returns nothing, which silently wiped gold metrics. Claims
     * are therefore judged in batches, and keypoint verdicts are OR-merged across them.
     */
    @Test
    void largeClaimSetsAreJudgedInBatchesAndCoverageIsMerged() {
        JudgeScorer scorer = scorer(request -> {
            String system = request.systemPrompt();
            if (system.contains("引用支撑")) {
                return response("{\"citations\":[{\"ref\":\"[1]\",\"verdict\":\"SUPPORT\"}]}");
            }
            if (system.contains("覆盖度")) {
                // Only later batches claim kp1 is covered; the merge must keep it true.
                boolean firstBatch = request.userPrompt().contains("c1:");
                return response("{\"coverage\":[{\"kpId\":\"kp1\",\"covered\":"
                        + (firstBatch ? "false" : "true")
                        + "},{\"kpId\":\"kp2\",\"covered\":false}]}");
            }
            throw new IllegalStateException("J3 must not run for keypoints questions");
        });

        JudgeResult result = scorer.score(keyPointsQuestion(), documentWithClaims(30));

        assertEquals(3, result.j2Calls(), "30 claims split into batches of 12");
        assertEquals(2, result.keyPointsJudged());
        assertEquals(1.0, result.coveredWeight(), 1e-9);
        assertEquals(1.6, result.totalWeight(), 1e-9);
        assertEquals(30, result.citationsJudged());
    }

    private static ReportDocument documentWithClaims(int count) {
        List<ReportDocument.Claim> claims = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            claims.add(new ReportDocument.Claim("c" + i, "结论 " + i, "SUPPORTED", 0.8,
                    List.of(new ReportDocument.Citation("[1]",
                            "https://a.example/" + i, "t", "引用 " + i))));
        }
        return new ReportDocument(
                List.of(new ReportDocument.Section("s1", "章节", claims)),
                List.of(),
                List.of(),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));
    }

    private JudgeScorer scorer(LlmPort port) {        EvalProperties properties = new EvalProperties();
        properties.setCacheDir(dir.toString());
        properties.getJudge().setModel("judge-test");
        JudgeClient client = new JudgeClient(properties, mapper,
                new JudgeCache(mapper, properties), prompts, port);
        return new JudgeScorer(client, prompts);
    }

    private static LlmResponse response(String content) {
        return new LlmResponse(content, new Usage(20, 5, 7), "judge-test", "stop");
    }

    private static EvalQuestion keyPointsQuestion() {
        return new EvalQuestion("q2", "2026-11-15", "regression", "x", "问题是什么？",
                "zh", "QUICK",
                new EvalQuestion.Gold("keypoints", true, "2026-11-15", null,
                        List.of(new EvalQuestion.KeyPoint("kp1", 1.0, "要点一", List.of(), List.of()),
                                new EvalQuestion.KeyPoint("kp2", 0.6, "要点二", List.of(), List.of())),
                        List.of()),
                false, "");
    }

    private static ReportDocument documentWithoutCitations() {
        return new ReportDocument(
                List.of(new ReportDocument.Section("s1", "章节", List.of(
                        new ReportDocument.Claim("c1", "结论一", "SUPPORTED", 0.8, List.of())))),
                List.of(),
                List.of(),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));
    }

    private static EvalQuestion question() {
        return new EvalQuestion("q1", "2026-11-15", "regression", "x", "问题是什么？",
                "zh", "QUICK",
                new EvalQuestion.Gold("exact", true, "2026-11-15", "金标答案",
                        List.of(new EvalQuestion.KeyPoint("kp1", 1.0, "要点一", List.of(), List.of()),
                                new EvalQuestion.KeyPoint("kp2", 0.6, "要点二", List.of(), List.of())),
                        List.of()),
                false, "");
    }

    private static ReportDocument document() {
        return new ReportDocument(
                List.of(new ReportDocument.Section("s1", "章节", List.of(
                        new ReportDocument.Claim("c1", "结论一", "SUPPORTED", 0.8, List.of(
                                new ReportDocument.Citation("[1]", "https://a.example/1", "t", "引用一"),
                                new ReportDocument.Citation("[2]", "https://b.example/2", "t", "引用二")))))),
                List.of(),
                List.of(new ReportDocument.Reference(1, "https://a.example/1", "t", "2026-11-15"),
                        new ReportDocument.Reference(2, "https://b.example/2", "t", "2026-11-15")),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));
    }
}
