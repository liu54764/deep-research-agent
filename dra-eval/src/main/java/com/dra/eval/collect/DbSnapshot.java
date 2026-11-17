package com.dra.eval.collect;

import com.dra.domain.model.TaskRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One task's persisted facts, read directly from MySQL. The evaluator never trusts the
 * report for these: invariants compare the rendered document against this snapshot.
 */
public record DbSnapshot(
        TaskRow task,
        List<DbClaim> claims,
        List<DbCitation> citations,
        List<DbSource> sources,
        int evidenceCount,
        long usageTokens,
        long usageCostMicros) {

    public DbSnapshot {
        claims = claims == null ? List.of() : List.copyOf(claims);
        citations = citations == null ? List.of() : List.copyOf(citations);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public List<DbCitation> citationsOf(String claimId) {
        return citations.stream().filter(c -> c.claimId().equals(claimId)).toList();
    }

    public long sourcesOk() {
        return sources.stream().filter(s -> "OK".equals(s.fetchStatus())).count();
    }

    /** Claim id → claim, for joining report-rendered claims back to persisted components. */
    public Map<String, DbClaim> byId() {
        Map<String, DbClaim> byId = new LinkedHashMap<>();
        for (DbClaim claim : claims) {
            byId.put(claim.id(), claim);
        }
        return byId;
    }

    public record DbClaim(
            String id,
            String sectionKey,
            String statement,
            int importance,
            String status,
            double confidence,
            Double judgeConfidence,
            Double ruleRatio,
            Double diversityScore) {

        /** Claims without persisted components (runs written before V3, or test fixtures). */
        public DbClaim(String id, String sectionKey, String statement, int importance,
                       String status, double confidence) {
            this(id, sectionKey, statement, importance, status, confidence, null, null, null);
        }
    }

    public record DbCitation(
            String claimId,
            String evidenceId,
            String sourceId,
            String url,
            String title,
            String quote,
            String origin) {
    }

    public record DbSource(String id, String url, String canonicalUrl, String fetchStatus) {
    }
}
