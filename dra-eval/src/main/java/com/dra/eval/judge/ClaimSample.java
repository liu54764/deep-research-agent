package com.dra.eval.judge;

/**
 * One rendered claim with its stored confidence and an external correctness verdict.
 * {@code source} records whether correctness came from J3 (gold) or J1 (citation support).
 */
public record ClaimSample(
        String questionId,
        String claimId,
        double confidence,
        boolean correct,
        String source) {
}
