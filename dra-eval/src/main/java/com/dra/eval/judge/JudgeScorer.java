package com.dra.eval.judge;

import com.dra.contracts.dto.ReportDocument;
import com.dra.eval.collect.DbSnapshot;
import com.dra.eval.dataset.EvalQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the three judge tasks from M9-eval.md §5.3:
 * J1 citation support (per claim, all citations in one call),
 * J2 gold coverage (per question), J3 correctness vs gold (exact questions only).
 *
 * <p>Nothing about internal state (status, confidence, verification rows) is sent to the
 * judge: it sees the question, the claim text, the quotes and the gold answer only.
 */
@Component
public class JudgeScorer {

    private static final Set<String> CITATION_VERDICTS =
            Set.of("SUPPORT", "PARTIAL", "REFUTE", "UNRELATED");
    private static final Set<String> GOLD_VERDICTS =
            Set.of("SUPPORTED", "CONTRADICTED", "NOT_IN_GOLD", "UNKNOWN");
    private static final int MAX_USER_CHARS = 12_000;
    private static final int MAX_QUOTE_CHARS = 800;
    private static final int MAX_STATEMENT_CHARS = 600;
    /**
     * A single J2/J3 call carrying every claim makes a reasoning judge spend its whole
     * output budget on hidden reasoning (observed: completionTokens == max, empty content),
     * so gold metrics silently vanish. Batching keeps each call small.
     */
    private static final int CLAIMS_PER_BATCH = 12;

    private final JudgeClient client;
    private final JudgePrompts prompts;

    public JudgeScorer(JudgeClient client, JudgePrompts prompts) {
        this.client = client;
        this.prompts = prompts;
    }

    public JudgeResult score(EvalQuestion question, ReportDocument document) {
        return score(question, document, Map.of());
    }

    /**
     * @param claimsById persisted claims keyed by id; supplies the confidence components that
     *                   the rendered document does not carry. Empty means "components unknown".
     */
    public JudgeResult score(EvalQuestion question, ReportDocument document,
                             Map<String, DbSnapshot.DbClaim> claimsById) {
        List<FlatClaim> claims = flatten(document);
        List<LabelItem> j1Candidates = new ArrayList<>();
        List<LabelItem> j2Candidates = new ArrayList<>();
        Map<String, Boolean> j1Correct = new LinkedHashMap<>();
        Map<String, Boolean> j3Correct = new LinkedHashMap<>();

        long promptTokens = 0;
        long completionTokens = 0;
        long costMicros = 0;
        int cacheHits = 0;

        int citationsJudged = 0;
        int support = 0;
        int partial = 0;
        int refute = 0;
        int unrelated = 0;
        int claimsWithCitations = 0;
        int claimsWithSupport = 0;
        int claimsWithInvalid = 0;
        int j1Calls = 0;
        int j2Calls = 0;
        int j3Calls = 0;
        int invalidOutputs = 0;

        for (FlatClaim claim : claims) {
            if (claim.citations().isEmpty()) {
                continue;
            }
            j1Calls++;
            JsonNode node;
            try {
                String user = prompts.render(prompts.user(JudgePrompts.J1), Map.of(
                        "question", truncate(question.question(), MAX_STATEMENT_CHARS),
                        "statement", truncate(claim.statement(), MAX_STATEMENT_CHARS),
                        "citations", formatCitations(claim.citations())));
                JudgeClient.Judged judged = client.askJson(question.id(), "j1", JudgePrompts.J1,
                        prompts.system(JudgePrompts.J1), truncate(user, MAX_USER_CHARS));
                promptTokens += judged.promptTokens();
                completionTokens += judged.completionTokens();
                costMicros += judged.costMicros();
                if (judged.cacheHit()) {
                    cacheHits++;
                }
                node = judged.result();
            } catch (JudgeClient.JudgeInvalidException e) {
                invalidOutputs++;
                continue;
            }

            Map<String, String> verdicts = parseCitationVerdicts(node);
            boolean claimJudged = false;
            boolean claimSupported = false;
            boolean claimInvalid = false;
            for (ReportDocument.Citation citation : claim.citations()) {
                String verdict = verdicts.get(citation.ref());
                if (verdict == null) {
                    verdict = verdicts.get("__all__");
                }
                if (verdict == null) {
                    invalidOutputs++;
                    continue;
                }
                claimJudged = true;
                citationsJudged++;
                switch (verdict) {
                    case "SUPPORT" -> {
                        support++;
                        claimSupported = true;
                    }
                    case "PARTIAL" -> partial++;
                    case "REFUTE" -> {
                        refute++;
                        claimInvalid = true;
                    }
                    default -> {
                        unrelated++;
                        claimInvalid = true;
                    }
                }
                j1Candidates.add(new LabelItem("J1", question.id(),
                        claim.id() + ":" + citation.ref(),
                        j1Label(question.question(), claim.statement(), citation),
                        verdict, null));
            }
            if (claimJudged) {
                claimsWithCitations++;
                if (claimSupported) {
                    claimsWithSupport++;
                }
                if (claimInvalid) {
                    claimsWithInvalid++;
                }
                // Calibration ground truth: supported and not contradicted by any citation.
                j1Correct.put(claim.id(), claimSupported && !claimInvalid);
            }
        }

        int keyPointsJudged = 0;
        double coveredWeight = 0.0;
        double totalWeight = 0.0;
        List<EvalQuestion.KeyPoint> keyPoints = question.gold().keyPoints();
        if (!keyPoints.isEmpty() && !claims.isEmpty()) {
            // Batched: a keypoint counts as covered if any batch says so, and only counts
            // as judged once at least one batch actually returned a verdict for it.
            Map<String, Boolean> coverage = new LinkedHashMap<>();
            Set<String> answered = new LinkedHashSet<>();
            for (List<FlatClaim> batch : batches(claims)) {
                j2Calls++;
                try {
                    String user = prompts.render(prompts.user(JudgePrompts.J2), Map.of(
                            "question", truncate(question.question(), MAX_STATEMENT_CHARS),
                            "keyPoints", formatKeyPoints(keyPoints),
                            "claims", formatClaims(batch)));
                    JudgeClient.Judged judged = client.askJson(question.id(), "j2", JudgePrompts.J2,
                            prompts.system(JudgePrompts.J2), truncate(user, MAX_USER_CHARS));
                    promptTokens += judged.promptTokens();
                    completionTokens += judged.completionTokens();
                    costMicros += judged.costMicros();
                    if (judged.cacheHit()) {
                        cacheHits++;
                    }
                    Map<String, Boolean> batchCoverage = parseCoverage(judged.result());
                    answered.addAll(batchCoverage.keySet());
                    batchCoverage.forEach((id, covered) ->
                            coverage.merge(id, covered, (left, right) -> left || right));
                } catch (JudgeClient.JudgeInvalidException e) {
                    invalidOutputs++;
                }
            }
            for (EvalQuestion.KeyPoint keyPoint : keyPoints) {
                if (!answered.contains(keyPoint.id())) {
                    invalidOutputs++;
                    continue;
                }
                boolean covered = Boolean.TRUE.equals(coverage.get(keyPoint.id()));
                keyPointsJudged++;
                totalWeight += keyPoint.weight();
                if (covered) {
                    coveredWeight += keyPoint.weight();
                }
                j2Candidates.add(new LabelItem("J2", question.id(), keyPoint.id(),
                        j2Label(question.question(), keyPoint, claims),
                        covered ? "COVERED" : "NOT_COVERED", null));
            }
        }

        int exactClaimsJudged = 0;
        int goldSupported = 0;
        int contradicted = 0;
        String goldAnswer = question.gold().goldAnswer();
        if (question.exact() && goldAnswer != null && !goldAnswer.isBlank() && !claims.isEmpty()) {
            Map<String, String> verdicts = new LinkedHashMap<>();
            for (List<FlatClaim> batch : batches(claims)) {
                j3Calls++;
                try {
                    String user = prompts.render(prompts.user(JudgePrompts.J3), Map.of(
                            "question", truncate(question.question(), MAX_STATEMENT_CHARS),
                            "goldAnswer", truncate(goldAnswer, MAX_STATEMENT_CHARS * 2),
                            "claims", formatClaims(batch)));
                    JudgeClient.Judged judged = client.askJson(question.id(), "j3", JudgePrompts.J3,
                            prompts.system(JudgePrompts.J3), truncate(user, MAX_USER_CHARS));
                    promptTokens += judged.promptTokens();
                    completionTokens += judged.completionTokens();
                    costMicros += judged.costMicros();
                    if (judged.cacheHit()) {
                        cacheHits++;
                    }
                    verdicts.putAll(parseGoldVerdicts(judged.result()));
                } catch (JudgeClient.JudgeInvalidException e) {
                    invalidOutputs++;
                }
            }
            for (FlatClaim claim : claims) {
                String verdict = verdicts.get(claim.id());
                if (verdict == null) {
                    invalidOutputs++;
                    continue;
                }
                exactClaimsJudged++;
                if ("SUPPORTED".equals(verdict)) {
                    goldSupported++;
                    j3Correct.put(claim.id(), true);
                } else if ("CONTRADICTED".equals(verdict)) {
                    contradicted++;
                    j3Correct.put(claim.id(), false);
                }
            }
        }

        boolean exactGold = question.exact() && goldAnswer != null && !goldAnswer.isBlank();
        List<ClaimSample> samples = new ArrayList<>();
        for (FlatClaim claim : claims) {
            Boolean correct = null;
            String source = null;
            if (exactGold && j3Correct.containsKey(claim.id())) {
                correct = j3Correct.get(claim.id());
                source = "J3";
            } else if (j1Correct.containsKey(claim.id())) {
                correct = j1Correct.get(claim.id());
                source = "J1";
            }
            if (correct != null) {
                DbSnapshot.DbClaim persisted = claimsById.get(claim.id());
                samples.add(new ClaimSample(question.id(), claim.id(), claim.confidence(),
                        correct, source,
                        persisted == null ? null : persisted.judgeConfidence(),
                        persisted == null ? null : persisted.ruleRatio(),
                        persisted == null ? null : persisted.diversityScore()));
            }
        }

        return new JudgeResult(
                citationsJudged, support, partial, refute, unrelated,
                claimsWithCitations, claimsWithSupport, claimsWithInvalid,
                keyPointsJudged, coveredWeight, totalWeight,
                exactClaimsJudged, goldSupported, contradicted,
                j1Calls, j2Calls, j3Calls,
                promptTokens, completionTokens, costMicros, cacheHits,
                invalidOutputs, j1Candidates, j2Candidates, samples);
    }

    private Map<String, String> parseCitationVerdicts(JsonNode node) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        JsonNode array = node.path("citations");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String ref = item.path("ref").asText("").trim();
                String verdict = normalize(item.path("verdict").asText(""));
                if (!ref.isEmpty() && CITATION_VERDICTS.contains(verdict)) {
                    verdicts.put(ref, verdict);
                }
            }
        }
        if (verdicts.isEmpty()) {
            String verdict = normalize(node.path("verdict").asText(""));
            if (CITATION_VERDICTS.contains(verdict)) {
                verdicts.put("__all__", verdict);
            }
        }
        return verdicts;
    }

    private Map<String, Boolean> parseCoverage(JsonNode node) {
        Map<String, Boolean> coverage = new LinkedHashMap<>();
        JsonNode array = node.path("coverage");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String kpId = item.path("kpId").asText("").trim();
                JsonNode covered = item.path("covered");
                if (!kpId.isEmpty() && covered.isBoolean()) {
                    coverage.put(kpId, covered.asBoolean());
                }
            }
        }
        return coverage;
    }

    private Map<String, String> parseGoldVerdicts(JsonNode node) {
        Map<String, String> verdicts = new LinkedHashMap<>();
        JsonNode array = node.path("claims");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String claimId = item.path("claimId").asText("").trim();
                String verdict = normalize(item.path("verdict").asText(""));
                if (!claimId.isEmpty() && GOLD_VERDICTS.contains(verdict)) {
                    verdicts.put(claimId, verdict);
                }
            }
        }
        return verdicts;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static List<FlatClaim> flatten(ReportDocument document) {
        List<FlatClaim> claims = new ArrayList<>();
        for (ReportDocument.Section section : document.sections()) {
            for (ReportDocument.Claim claim : section.claims()) {
                claims.add(new FlatClaim(claim.id(), claim.statement(), claim.confidence(),
                        claim.citations()));
            }
        }
        return claims;
    }

    private static String formatCitations(List<ReportDocument.Citation> citations) {
        StringBuilder out = new StringBuilder();
        for (ReportDocument.Citation citation : citations) {
            out.append(citation.ref()).append(" url=").append(nullToEmpty(citation.url()));
            if (citation.title() != null && !citation.title().isBlank()) {
                out.append(" | title=").append(truncate(citation.title(), 200));
            }
            out.append("\nquote: ").append(truncate(nullToEmpty(citation.quote()), MAX_QUOTE_CHARS))
                    .append('\n');
        }
        return out.toString();
    }

    private static String formatKeyPoints(List<EvalQuestion.KeyPoint> keyPoints) {
        StringBuilder out = new StringBuilder();
        for (EvalQuestion.KeyPoint keyPoint : keyPoints) {
            out.append("- ").append(keyPoint.id()).append(" (weight ")
                    .append(String.format(Locale.ROOT, "%.1f", keyPoint.weight())).append("): ")
                    .append(truncate(keyPoint.statement(), MAX_STATEMENT_CHARS)).append('\n');
        }
        return out.toString();
    }

    private static String formatClaims(List<FlatClaim> claims) {
        StringBuilder out = new StringBuilder();
        int limit = Math.min(claims.size(), 40);
        for (int i = 0; i < limit; i++) {
            FlatClaim claim = claims.get(i);
            out.append("- ").append(claim.id()).append(": ")
                    .append(truncate(claim.statement(), MAX_STATEMENT_CHARS)).append('\n');
        }
        return out.toString();
    }

    /** Split claims so each judge call stays small enough for a reasoning model. */
    static List<List<FlatClaim>> batches(List<FlatClaim> claims) {
        List<List<FlatClaim>> batches = new ArrayList<>();
        for (int start = 0; start < claims.size(); start += CLAIMS_PER_BATCH) {
            batches.add(claims.subList(start, Math.min(claims.size(), start + CLAIMS_PER_BATCH)));
        }
        return batches;
    }

    private static String j1Label(String question, String statement,
                                  ReportDocument.Citation citation) {
        return "问题: " + truncate(question, MAX_STATEMENT_CHARS) + "\n"
                + "结论: " + truncate(statement, MAX_STATEMENT_CHARS) + "\n"
                + "引用: " + citation.ref() + " " + nullToEmpty(citation.url()) + "\n"
                + "原文: " + truncate(nullToEmpty(citation.quote()), MAX_QUOTE_CHARS);
    }

    private static String j2Label(String question, EvalQuestion.KeyPoint keyPoint,
                                  List<FlatClaim> claims) {
        return "问题: " + truncate(question, MAX_STATEMENT_CHARS) + "\n"
                + "要点 " + keyPoint.id() + ": " + truncate(keyPoint.statement(), MAX_STATEMENT_CHARS) + "\n"
                + "报告结论:\n" + formatClaims(claims);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record FlatClaim(String id, String statement, double confidence,
                             List<ReportDocument.Citation> citations) {
    }
}
