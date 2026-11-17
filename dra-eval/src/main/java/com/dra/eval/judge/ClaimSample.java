package com.dra.eval.judge;

/**
 * One rendered claim with its stored confidence and an external correctness verdict.
 * {@code source} records whether correctness came from J3 (gold) or J1 (citation support).
 * The components are null for runs written before V3; calibration then falls back to the
 * stored confidence only.
 */
public record ClaimSample(
        String questionId,
        String claimId,
        double confidence,
        boolean correct,
        String source,
        Double judgeConfidence,
        Double ruleRatio,
        Double diversityScore) {

    public ClaimSample(String questionId, String claimId, double confidence,
                       boolean correct, String source) {
        this(questionId, claimId, confidence, correct, source, null, null, null);
    }

    public boolean hasComponents() {
        return judgeConfidence != null && ruleRatio != null && diversityScore != null;
    }
}
