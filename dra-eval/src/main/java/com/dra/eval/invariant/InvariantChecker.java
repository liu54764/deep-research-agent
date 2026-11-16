package com.dra.eval.invariant;

import com.dra.contracts.dto.ReportDocument;
import com.dra.domain.model.TaskRow;
import com.dra.domain.util.UrlCanonicalizer;
import com.dra.eval.collect.DbSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed invariants (M9-eval.md §3.6). These are not accuracy metrics: a violation
 * means the product broke a promise, so the whole run is invalid (exit code 2).
 */
public final class InvariantChecker {

    private static final Pattern RESIDUAL_MARKER = Pattern.compile("\\[\\[[^\\]]*]]");
    private static final Pattern REF_MARKER = Pattern.compile("\\[(\\d+)]");
    private static final Set<String> FORBIDDEN_IN_BODY = Set.of("REFUTED", "UNSUPPORTED");
    private static final int MAX_DETAILS_PER_INVARIANT = 5;

    public List<Violation> check(ReportDocument document,
                                 String markdown,
                                 DbSnapshot snapshot,
                                 List<String> taskWarnings) {
        List<Violation> violations = new ArrayList<>();
        if (document == null) {
            violations.add(new Violation("I0", "report document is missing"));
            return violations;
        }

        Map<String, List<DbSnapshot.DbCitation>> byClaim = new LinkedHashMap<>();
        for (DbSnapshot.DbCitation citation : snapshot.citations()) {
            byClaim.computeIfAbsent(citation.claimId(), id -> new ArrayList<>()).add(citation);
        }
        Map<String, Integer> perInvariant = new LinkedHashMap<>();

        for (ReportDocument.Section section : document.sections()) {
            for (ReportDocument.Claim claim : section.claims()) {
                // I3: a rendered claim must carry at least one citation.
                if (claim.citations().isEmpty()) {
                    add(violations, perInvariant, "I3", "claim " + claim.id() + " has no citation");
                }
                // I4: refuted/unverified claims never enter the body.
                if (claim.status() != null && FORBIDDEN_IN_BODY.contains(claim.status())) {
                    add(violations, perInvariant, "I4",
                            "claim " + claim.id() + " with status " + claim.status() + " is rendered in body");
                }
                List<DbSnapshot.DbCitation> dbCitations = byClaim.getOrDefault(claim.id(), List.of());
                for (ReportDocument.Citation citation : claim.citations()) {
                    // I1: the citation must resolve to a persisted (claim, evidence) pair.
                    if (!matchesAny(claim.id(), citation, dbCitations)) {
                        add(violations, perInvariant, "I1",
                                "citation " + citation.ref() + " of claim " + claim.id()
                                        + " does not resolve to a persisted evidence row");
                    }
                    // I2: the URL must come from this task's source table.
                    if (!hasSource(snapshot, citation.url())) {
                        add(violations, perInvariant, "I2",
                                "citation " + citation.ref() + " url " + citation.url()
                                        + " is not among task sources");
                    }
                }
            }
        }

        // I5: no unresolved [[claimId]] markers left in the rendered markdown.
        checkResidualMarkers(markdown, violations, perInvariant);

        // I6: references are 1..N, unique, and numbered by first appearance.
        checkReferences(document, markdown, violations, perInvariant);

        // I7: deterministic stats must match the database.
        checkStats(document, snapshot, violations, perInvariant);

        // I8: meta must agree with the task row, and report warnings must cover task warnings.
        checkMeta(document, snapshot.task(), taskWarnings, violations, perInvariant);

        return violations;
    }

    private void checkResidualMarkers(String markdown, List<Violation> violations,
                                      Map<String, Integer> perInvariant) {
        Matcher matcher = RESIDUAL_MARKER.matcher(markdown == null ? "" : markdown);
        while (matcher.find()) {
            add(violations, perInvariant, "I5", "unresolved marker " + matcher.group());
        }
    }

    private void checkReferences(ReportDocument document, String markdown,
                                 List<Violation> violations, Map<String, Integer> perInvariant) {
        List<ReportDocument.Reference> references = document.references();
        Set<String> urls = new HashSet<>();
        int previousFirstIndex = -1;
        for (int i = 0; i < references.size(); i++) {
            ReportDocument.Reference reference = references.get(i);
            if (reference.n() != i + 1) {
                add(violations, perInvariant, "I6",
                        "reference numbering is not contiguous at position " + (i + 1)
                                + " (found " + reference.n() + ")");
            }
            String canonical = canonical(reference.url());
            if (!urls.add(canonical)) {
                add(violations, perInvariant, "I6", "duplicate reference url " + reference.url());
            }
            int firstIndex = markdown == null ? -1 : markdown.indexOf("[" + reference.n() + "]");
            if (firstIndex < 0) {
                add(violations, perInvariant, "I6",
                        "reference [" + reference.n() + "] never appears in the body");
            } else if (firstIndex < previousFirstIndex) {
                add(violations, perInvariant, "I6",
                        "reference [" + reference.n() + "] first appears before the previous one");
            } else {
                previousFirstIndex = firstIndex;
            }
        }
        // A marker in the body pointing at a non-existent reference is a renderer bug.
        Matcher matcher = REF_MARKER.matcher(markdown == null ? "" : markdown);
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            if (n < 1 || n > references.size()) {
                add(violations, perInvariant, "I6", "body references [" + n + "] but there are "
                        + references.size() + " references");
            }
        }
    }

    private void checkStats(ReportDocument document, DbSnapshot snapshot,
                            List<Violation> violations, Map<String, Integer> perInvariant) {
        ReportDocument.Stats stats = document.stats();
        if (stats == null) {
            add(violations, perInvariant, "I7", "report stats are missing");
            return;
        }
        expect(violations, perInvariant, "claims", stats.claims(), snapshot.claims().size());
        expect(violations, perInvariant, "evidence", stats.evidence(), snapshot.evidenceCount());
        expect(violations, perInvariant, "sourcesFetched", stats.sourcesFetched(), (int) snapshot.sourcesOk());

        long expectedTokens = Math.max(snapshot.task().usedTokens(), snapshot.usageTokens());
        // The report is generated before the task is finalized, so stats may lag the
        // terminal counters. Over-reporting is a bug; under-reporting is an ordering artifact.
        if (stats.tokens() > expectedTokens + tolerance(expectedTokens, 50)) {
            add(violations, perInvariant, "I7", "stats.tokens=" + stats.tokens()
                    + " exceeds database total " + expectedTokens);
        }
        long expectedCost = Math.max(snapshot.task().usedCostMicros(), snapshot.usageCostMicros());
        if (stats.costMicros() > expectedCost + tolerance(expectedCost, 100)) {
            add(violations, perInvariant, "I7", "stats.costMicros=" + stats.costMicros()
                    + " exceeds database total " + expectedCost);
        }
        long renderable = snapshot.claims().stream()
                .filter(c -> !FORBIDDEN_IN_BODY.contains(c.status()))
                .count();
        double expectedRatio = snapshot.claims().isEmpty()
                ? 0.0
                : (double) renderable / snapshot.claims().size();
        if (Math.abs(stats.verifiedRatio() - expectedRatio) > 0.01) {
            add(violations, perInvariant, "I7", "stats.verifiedRatio=" + stats.verifiedRatio()
                    + " but database says " + expectedRatio);
        }
    }

    private void checkMeta(ReportDocument document, TaskRow task, List<String> taskWarnings,
                           List<Violation> violations, Map<String, Integer> perInvariant) {
        String metaStopReason = document.meta().getOrDefault("stopReason", "");
        String taskStopReason = task == null || task.stopReason() == null ? "" : task.stopReason();
        if (!metaStopReason.equals(taskStopReason)) {
            add(violations, perInvariant, "I8", "report meta stopReason='" + metaStopReason
                    + "' but task says '" + taskStopReason + "'");
        }
        String statsStopReason = document.stats().stopReason() == null ? "" : document.stats().stopReason();
        if (!statsStopReason.equals(metaStopReason)) {
            add(violations, perInvariant, "I8", "report stats stopReason='" + statsStopReason
                    + "' differs from meta '" + metaStopReason + "'");
        }
        Set<String> reportWarnings = new HashSet<>(document.stats().warnings());
        for (String warning : taskWarnings == null ? List.<String>of() : taskWarnings) {
            if (!reportWarnings.contains(warning)) {
                add(violations, perInvariant, "I8",
                        "task warning '" + warning + "' is missing from the report");
            }
        }
    }

    private boolean matchesAny(String claimId, ReportDocument.Citation citation,
                               List<DbSnapshot.DbCitation> dbCitations) {
        String citationUrl = canonical(citation.url());
        String citationQuote = citation.quote() == null ? "" : citation.quote().trim();
        for (DbSnapshot.DbCitation dbCitation : dbCitations) {
            if (!claimId.equals(dbCitation.claimId())) {
                continue;
            }
            boolean sameUrl = citationUrl.equals(canonical(dbCitation.url()));
            boolean sameQuote = citationQuote.isEmpty()
                    || citationQuote.equals(dbCitation.quote() == null ? "" : dbCitation.quote().trim());
            if (sameUrl && sameQuote) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSource(DbSnapshot snapshot, String url) {
        String canonical = canonical(url);
        for (DbSnapshot.DbSource source : snapshot.sources()) {
            if (canonical.equals(canonical(source.url()))
                    || canonical.equals(canonical(source.canonicalUrl()))) {
                return true;
            }
        }
        return false;
    }

    private static void expect(List<Violation> violations, Map<String, Integer> perInvariant,
                               String field, int reported, int actual) {
        if (reported != actual) {
            add(violations, perInvariant, "I7",
                    "stats." + field + "=" + reported + " but database says " + actual);
        }
    }

    private static long tolerance(long actual, long floorTolerance) {
        return Math.max(floorTolerance, Math.round(Math.abs(actual) * 0.01));
    }

    private static void add(List<Violation> violations, Map<String, Integer> perInvariant,
                            String id, String detail) {
        int seen = perInvariant.merge(id, 1, Integer::sum);
        if (seen <= MAX_DETAILS_PER_INVARIANT) {
            violations.add(new Violation(id, detail));
        } else if (seen == MAX_DETAILS_PER_INVARIANT + 1) {
            violations.add(new Violation(id, "further violations of " + id + " suppressed"));
        }
    }

    private static String canonical(String url) {
        if (url == null) {
            return "";
        }
        try {
            return UrlCanonicalizer.canonicalize(url);
        } catch (IllegalArgumentException e) {
            return url.trim();
        }
    }

    public record Violation(String id, String detail) {
    }
}
