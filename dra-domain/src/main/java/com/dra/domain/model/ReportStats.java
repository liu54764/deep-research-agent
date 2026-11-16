package com.dra.domain.model;

public record ReportStats(
        int sourcesFetched,
        int sourcesFailed,
        int evidenceCount,
        int claimsTotal,
        int claimsVerified,
        long tokens,
        long costMicros,
        long durationMs) {

    public double verifiedRatio() {
        return claimsTotal == 0 ? 0.0 : (double) claimsVerified / claimsTotal;
    }
}
