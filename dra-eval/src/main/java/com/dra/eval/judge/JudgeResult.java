package com.dra.eval.judge;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/** Judge-side metrics for one question. Candidate labels are sampling input, not output. */
public record JudgeResult(
        int citationsJudged,
        int support,
        int partial,
        int refute,
        int unrelated,
        int claimsWithCitations,
        int claimsWithSupport,
        int claimsWithInvalid,
        int keyPointsJudged,
        double coveredWeight,
        double totalWeight,
        int exactClaimsJudged,
        int goldSupported,
        int contradicted,
        int j1Calls,
        int j2Calls,
        int j3Calls,
        long promptTokens,
        long completionTokens,
        long costMicros,
        int cacheHits,
        int invalidOutputs,
        @JsonIgnore List<LabelItem> j1Candidates,
        @JsonIgnore List<LabelItem> j2Candidates,
        @JsonIgnore List<ClaimSample> claimSamples) {

    public JudgeResult {
        j1Candidates = j1Candidates == null ? List.of() : List.copyOf(j1Candidates);
        j2Candidates = j2Candidates == null ? List.of() : List.copyOf(j2Candidates);
        claimSamples = claimSamples == null ? List.of() : List.copyOf(claimSamples);
    }

    public double carStrict() {
        return citationsJudged == 0 ? 0.0 : (double) support / citationsJudged;
    }

    public double carWeighted() {
        return citationsJudged == 0 ? 0.0 : (support + 0.5 * partial) / citationsJudged;
    }

    public double hfr() {
        return claimsWithCitations == 0 ? 0.0
                : 1.0 - (double) claimsWithInvalid / claimsWithCitations;
    }

    public double csr() {
        return claimsWithCitations == 0 ? 0.0
                : (double) claimsWithSupport / claimsWithCitations;
    }

    public double gcr() {
        return totalWeight == 0 ? 0.0 : coveredWeight / totalWeight;
    }

    public double contradictionRate() {
        return exactClaimsJudged == 0 ? 0.0 : (double) contradicted / exactClaimsJudged;
    }

    public double claimPrecision() {
        return exactClaimsJudged == 0 ? 0.0 : (double) goldSupported / exactClaimsJudged;
    }

    public static JudgeResult empty() {
        return new JudgeResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
    }
}
