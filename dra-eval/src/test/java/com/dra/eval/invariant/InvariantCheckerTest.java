package com.dra.eval.invariant;

import com.dra.contracts.dto.ReportDocument;
import com.dra.contracts.model.Depth;
import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.TaskRow;
import com.dra.eval.collect.DbSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantCheckerTest {

    private static final String URL = "https://example.com/post";
    private static final String QUOTE = "the quick brown fox";

    private final InvariantChecker checker = new InvariantChecker();

    @Test
    void healthyReportHasNoViolations() {
        List<InvariantChecker.Violation> violations =
                checker.check(document(), markdown(), snapshot(URL, QUOTE), List.of());

        assertTrue(violations.isEmpty(), () -> "unexpected violations: " + violations);
    }

    @Test
    void claimWithoutCitationViolatesI3() {
        List<InvariantChecker.Violation> violations =
                checker.check(document(claim(List.of())), markdown(), snapshot(URL, QUOTE), List.of());

        assertHas(violations, "I3");
    }

    @Test
    void refutedClaimInBodyViolatesI4() {
        ReportDocument document = document(claimWithStatus("REFUTED", List.of(citation())));

        assertHas(checker.check(document, markdown(), snapshot(URL, QUOTE), List.of()), "I4");
    }

    @Test
    void residualMarkerViolatesI5() {
        String body = "# t\n\n正文 [[deadbeef]] [1]\n\n## 参考来源\n\n[1] " + URL;

        assertHas(checker.check(document(), body, snapshot(URL, QUOTE), List.of()), "I5");
    }

    @Test
    void citationNotInDatabaseViolatesI1() {
        ReportDocument document = document(claim(List.of(
                new ReportDocument.Citation("[1]", URL, "t", "a different quote"))));

        assertHas(checker.check(document, markdown(), snapshot(URL, QUOTE), List.of()), "I1");
    }

    @Test
    void citationUrlOutsideSourcesViolatesI2() {
        DbSnapshot snapshot = snapshot("https://other.example.com/x", QUOTE);

        assertHas(checker.check(document(), markdown(), snapshot, List.of()), "I2");
    }

    @Test
    void referenceNumberingViolatesI6() {
        ReportDocument document = new ReportDocument(
                List.of(section(claim(List.of(citation())))),
                List.of(),
                List.of(new ReportDocument.Reference(2, URL, "title", "2026-11-15")),
                stats(),
                Map.of("stopReason", "COVERAGE_REACHED"));

        assertHas(checker.check(document, markdown(), snapshot(URL, QUOTE), List.of()), "I6");
    }

    @Test
    void statsMismatchViolatesI7() {
        ReportDocument document = new ReportDocument(
                List.of(section(claim(List.of(citation())))),
                List.of(),
                List.of(new ReportDocument.Reference(1, URL, "title", "2026-11-15")),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 7, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));

        assertHas(checker.check(document, markdown(), snapshot(URL, QUOTE), List.of()), "I7");
    }

    @Test
    void underReportedTokensAreAnOrderingArtifactNotAViolation() {
        ReportDocument document = new ReportDocument(
                List.of(section(claim(List.of(citation())))),
                List.of(),
                List.of(new ReportDocument.Reference(1, URL, "title", "2026-11-15")),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 0, 0, 1, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));

        List<InvariantChecker.Violation> violations =
                checker.check(document, markdown(), snapshot(URL, QUOTE), List.of());

        assertTrue(violations.stream().noneMatch(v -> v.id().equals("I7")),
                () -> "unexpected I7: " + violations);
    }

    @Test
    void stopReasonMismatchViolatesI8() {
        ReportDocument document = new ReportDocument(
                List.of(section(claim(List.of(citation())))),
                List.of(),
                List.of(new ReportDocument.Reference(1, URL, "title", "2026-11-15")),
                stats(),
                Map.of("stopReason", "MAX_ROUNDS"));

        assertHas(checker.check(document, markdown(), snapshot(URL, QUOTE), List.of()), "I8");
    }

    @Test
    void taskWarningMissingFromReportViolatesI8() {
        assertHas(checker.check(document(), markdown(), snapshot(URL, QUOTE),
                List.of("FETCH_UNSUPPORTED")), "I8");
    }

    @Test
    void violationDetailsAreCapped() {
        ReportDocument document = new ReportDocument(
                List.of(new ReportDocument.Section("s1", "S", List.of(
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of()),
                        claimWithStatus("SUPPORTED", List.of())))),
                List.of(),
                List.of(),
                statsWithClaims(1),
                Map.of("stopReason", "COVERAGE_REACHED"));

        List<InvariantChecker.Violation> violations =
                checker.check(document, markdown(), snapshot(URL, QUOTE), List.of());
        long i3 = violations.stream().filter(v -> v.id().equals("I3")).count();

        assertEquals(6, i3, "five details plus one suppression line");
    }

    private static void assertHas(List<InvariantChecker.Violation> violations, String id) {
        assertTrue(violations.stream().anyMatch(v -> v.id().equals(id)),
                () -> "expected " + id + " in " + violations);
    }

    private static ReportDocument document() {
        return document(claim(List.of(citation())));
    }

    private static ReportDocument document(ReportDocument.Claim claim) {
        return new ReportDocument(
                List.of(section(claim)),
                List.of(),
                List.of(new ReportDocument.Reference(1, URL, "title", "2026-11-15")),
                stats(),
                Map.of("stopReason", "COVERAGE_REACHED"));
    }

    private static ReportDocument.Section section(ReportDocument.Claim claim) {
        return new ReportDocument.Section("s1", "Section", List.of(claim));
    }

    private static ReportDocument.Claim claim(List<ReportDocument.Citation> citations) {
        return claimWithStatus("SUPPORTED", citations);
    }

    private static ReportDocument.Claim claimWithStatus(String status,
                                                         List<ReportDocument.Citation> citations) {
        return new ReportDocument.Claim("c1", "the statement", status, 0.8, citations);
    }

    private static ReportDocument.Citation citation() {
        return new ReportDocument.Citation("[1]", URL, "title", QUOTE);
    }

    private static ReportDocument.Stats stats() {
        return new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, 1, 1.0, List.of());
    }

    private static ReportDocument.Stats statsWithClaims(int claims) {
        return new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, claims, 1.0, List.of());
    }

    private static String markdown() {
        return "# report\n\n正文 [1]\n\n## 参考来源\n\n[1] " + URL + "\n";
    }

    private static DbSnapshot snapshot(String sourceUrl, String quote) {
        TaskRow task = new TaskRow("t1", "idem", "hash", "question", TaskStatus.SUCCEEDED,
                Depth.QUICK, 1000, 0, 100, 10, "wf", "run", "COVERAGE_REACHED",
                null, null, "r1", 0L, 0L, 1L);
        return new DbSnapshot(
                task,
                List.of(new DbSnapshot.DbClaim("c1", "s1", "the statement", 1, "SUPPORTED", 0.8)),
                List.of(new DbSnapshot.DbCitation("c1", "e1", "src1", sourceUrl, "title", quote, "FULL_TEXT")),
                List.of(new DbSnapshot.DbSource("src1", sourceUrl, sourceUrl, "OK")),
                1, 100, 10);
    }
}
