package com.dra.eval.report;

import com.dra.eval.invariant.InvariantChecker;
import com.dra.eval.runner.QuestionResult;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregate view written to {@code metrics.json}. Rates are micro-averaged. */
public record EvalMetrics(
        int questions,
        int succeeded,
        int failed,
        int skipped,
        long latencyP50Ms,
        long latencyP95Ms,
        long costMicros,
        long tokens,
        int claims,
        int citations,
        int anchoredCitations,
        int fullTextCitations,
        double anchorRate,
        int urlSampled,
        int urlLive,
        int urlDead,
        int urlUnverifiable,
        double urlLiveRate,
        int citationsJudged,
        int citationSupport,
        int citationPartial,
        int citationRefute,
        int citationUnrelated,
        int claimsWithCitations,
        double carStrict,
        double carWeighted,
        double hfr,
        double csr,
        int keyPointsJudged,
        double coveredWeight,
        double totalWeight,
        double gcr,
        /** Questions whose J2 verdict parsed; GCR only covers these. */
        int gcrQuestions,
        int exactClaimsJudged,
        int contradicted,
        double contradictionRate,
        double claimPrecision,
        int judgeCalls,
        int judgeCacheHits,
        int judgeInvalidOutputs,
        long judgeCostMicros,
        long judgeTokens,
        int invariantViolations,
        Map<String, Integer> violationsByInvariant,
        long verifiedQuestions) {

    public static EvalMetrics of(List<QuestionResult> results, long verifiedQuestions) {
        List<Long> latencies = results.stream()
                .filter(QuestionResult::succeeded)
                .map(QuestionResult::latencyMs)
                .sorted()
                .toList();
        int succeeded = (int) results.stream().filter(QuestionResult::succeeded).count();
        int skipped = (int) results.stream().filter(QuestionResult::skipped).count();
        int failed = results.size() - succeeded - skipped;

        long cost = results.stream().mapToLong(QuestionResult::costMicros).sum();
        long tokens = results.stream().mapToLong(QuestionResult::tokens).sum();
        int claims = results.stream().mapToInt(QuestionResult::claims).sum();
        int citations = results.stream().mapToInt(QuestionResult::citations).sum();
        int anchored = results.stream().mapToInt(r -> r.rule().anchored()).sum();
        int fullText = results.stream().mapToInt(r -> r.rule().fullTextCitations()).sum();
        int urlSampled = results.stream().mapToInt(r -> r.rule().urls().sampled()).sum();
        int urlLive = results.stream().mapToInt(r -> r.rule().urls().live()).sum();
        int urlDead = results.stream().mapToInt(r -> r.rule().urls().dead()).sum();
        int urlUnverifiable = results.stream().mapToInt(r -> r.rule().urls().unverifiable()).sum();

        int citationsJudged = results.stream().mapToInt(r -> r.judge().citationsJudged()).sum();
        int support = results.stream().mapToInt(r -> r.judge().support()).sum();
        int partial = results.stream().mapToInt(r -> r.judge().partial()).sum();
        int refute = results.stream().mapToInt(r -> r.judge().refute()).sum();
        int unrelated = results.stream().mapToInt(r -> r.judge().unrelated()).sum();
        int claimsWithCitations = results.stream().mapToInt(r -> r.judge().claimsWithCitations()).sum();
        int claimsWithSupport = results.stream().mapToInt(r -> r.judge().claimsWithSupport()).sum();
        int claimsWithInvalid = results.stream().mapToInt(r -> r.judge().claimsWithInvalid()).sum();
        int keyPointsJudged = results.stream().mapToInt(r -> r.judge().keyPointsJudged()).sum();
        double coveredWeight = results.stream().mapToDouble(r -> r.judge().coveredWeight()).sum();
        double totalWeight = results.stream().mapToDouble(r -> r.judge().totalWeight()).sum();
        // A failed J2 contributes 0/0 and would otherwise vanish from the ratio, making
        // the aggregate look complete when only a subset of questions was measured.
        int gcrQuestions = (int) results.stream().filter(r -> r.judge().totalWeight() > 0).count();
        int exactClaimsJudged = results.stream().mapToInt(r -> r.judge().exactClaimsJudged()).sum();
        int goldSupported = results.stream().mapToInt(r -> r.judge().goldSupported()).sum();
        int contradicted = results.stream().mapToInt(r -> r.judge().contradicted()).sum();
        int judgeCalls = results.stream().mapToInt(r -> r.judge().j1Calls()
                + r.judge().j2Calls() + r.judge().j3Calls()).sum();
        int judgeCacheHits = results.stream().mapToInt(r -> r.judge().cacheHits()).sum();
        int judgeInvalid = results.stream().mapToInt(r -> r.judge().invalidOutputs()).sum();
        long judgeCost = results.stream().mapToLong(r -> r.judge().costMicros()).sum();
        long judgeTokens = results.stream()
                .mapToLong(r -> r.judge().promptTokens() + r.judge().completionTokens()).sum();

        Map<String, Integer> byInvariant = new TreeMap<>();
        int violations = 0;
        for (QuestionResult result : results) {
            for (InvariantChecker.Violation violation : result.violations()) {
                violations++;
                byInvariant.merge(violation.id(), 1, Integer::sum);
            }
        }

        return new EvalMetrics(
                results.size(), succeeded, failed, skipped,
                percentile(latencies, 0.50), percentile(latencies, 0.95),
                cost, tokens, claims, citations,
                anchored, fullText, fullText == 0 ? 0.0 : (double) anchored / fullText,
                urlSampled, urlLive, urlDead, urlUnverifiable,
                urlLive + urlDead == 0 ? 0.0 : (double) urlLive / (urlLive + urlDead),
                citationsJudged, support, partial, refute, unrelated, claimsWithCitations,
                citationsJudged == 0 ? 0.0 : (double) support / citationsJudged,
                citationsJudged == 0 ? 0.0 : (support + 0.5 * partial) / citationsJudged,
                claimsWithCitations == 0 ? 0.0 : 1.0 - (double) claimsWithInvalid / claimsWithCitations,
                claimsWithCitations == 0 ? 0.0 : (double) claimsWithSupport / claimsWithCitations,
                keyPointsJudged, coveredWeight, totalWeight,
                totalWeight == 0 ? 0.0 : coveredWeight / totalWeight,
                gcrQuestions,
                exactClaimsJudged, contradicted,
                exactClaimsJudged == 0 ? 0.0 : (double) contradicted / exactClaimsJudged,
                exactClaimsJudged == 0 ? 0.0 : (double) goldSupported / exactClaimsJudged,
                judgeCalls, judgeCacheHits, judgeInvalid, judgeCost, judgeTokens,
                violations, byInvariant, verifiedQuestions);
    }

    public boolean judgeRuns() {
        return judgeCalls > 0;
    }

    static long percentile(List<Long> sortedValues, double p) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }
}
