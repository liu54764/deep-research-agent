package com.dra.worker.report;

import com.dra.contracts.dto.ReportDocument;
import com.dra.domain.model.ReportBundle;
import com.dra.domain.model.ReportCitation;
import com.dra.domain.model.ReportClaim;
import com.dra.domain.model.ReportConflict;
import com.dra.domain.model.ReportPosition;
import com.dra.domain.model.ReportSection;
import com.dra.domain.model.ReportStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportRendererTest {

    private final ReportRenderer renderer = new ReportRenderer();

    @Test
    void resolvesMarkersToNumberedReferencesAndDedupesUrls() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9,
                        citation("https://a.test/1"), citation("https://b.test/2")),
                claim("claim-2", "s1", "PARTIAL", 0.6, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle,
                "Temporal replays the event history [[claim-1]]. This is not free [[claim-2]].",
                "2026-11-11", Map.of("depth", "QUICK"));

        assertEquals(0, rendered.strippedMarkers());
        assertTrue(rendered.markdown().contains("replays the event history [1][2]."));
        assertTrue(rendered.markdown().contains("not free [1]."));
        assertEquals(2, rendered.document().references().size());
        assertEquals("https://a.test/1", rendered.document().references().get(0).url());
        assertEquals("https://b.test/2", rendered.document().references().get(1).url());
    }

    @Test
    void stripsUnknownMarkersAndRecordsWarning() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle,
                "Supported fact [[claim-1]]. Invented fact [[claim-999]].",
                "2026-11-11", Map.of());

        assertEquals(1, rendered.strippedMarkers());
        assertTrue(rendered.warnings().contains("REPORT_MARKER_STRIPPED:claim-999"));
        assertFalse(rendered.markdown().contains("claim-999"));
        assertTrue(rendered.markdown().contains("1 处引用标记无法解析，已删除"));
        assertTrue(rendered.markdown().contains("Supported fact [1]."));
    }

    @Test
    void keepsRefutedClaimsOutOfTheBodyAndIntoTheAppendix() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1")),
                claim("claim-2", "s1", "REFUTED", 0.2, citation("https://c.test/3"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle,
                "Only this is a fact [[claim-1]][[claim-2]].",
                "2026-11-11", Map.of());

        String markdown = rendered.markdown();
        assertTrue(markdown.contains("## 局限与未采纳结论"));
        assertTrue(markdown.contains("[REFUTED]"));
        int bodyIndex = markdown.indexOf("Only this is a fact");
        assertTrue(bodyIndex > 0);
        assertTrue(markdown.indexOf("[REFUTED]") > bodyIndex);
        assertEquals(1, rendered.document().references().size());
        assertEquals(1, rendered.document().sections().get(0).claims().size());
    }

    @Test
    void failsClosedWhenNoClaimSurvivesVerification() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "UNSUPPORTED", 0.1, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle,
                "The model wrote prose anyway [[claim-1]].", "2026-11-11", Map.of());

        assertTrue(rendered.markdown().contains("未形成通过校验的可引用结论"));
        assertTrue(rendered.warnings().contains("REPORT_NO_VERIFIED_CLAIMS"));
        assertTrue(rendered.warnings().contains("REPORT_MARKER_STRIPPED:claim-1"));
        assertTrue(rendered.document().references().isEmpty());
    }

    @Test
    void rendersConflictsWithoutAdjudicating() {
        ReportBundle bundle = bundle(
                List.of(claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1")),
                        claim("claim-2", "s2", "SUPPORTED", 0.8, citation("https://b.test/2"))));
        ReportBundle withConflict = new ReportBundle(
                bundle.taskId(), bundle.question(), bundle.depth(), bundle.stopReason(),
                bundle.warnings(), bundle.sections(), bundle.claims(),
                List.of(new ReportConflict("Temporal vs LangGraph",
                        "来源对长任务的适用场景存在分歧",
                        List.of(new ReportPosition("claim-1", "", "支持长期编排", List.of("https://a.test/1")),
                                new ReportPosition("claim-2", "", "认为编排成本过高", List.of("https://b.test/2"))))),
                bundle.stats());

        ReportRenderer.Rendered rendered = renderer.render(withConflict,
                "Body [[claim-1]].", "2026-11-11", Map.of());

        assertTrue(rendered.markdown().contains("## 争议与反证"));
        assertTrue(rendered.markdown().contains("Temporal vs LangGraph"));
        assertEquals(1, rendered.document().conflicts().size());
        assertEquals("支持长期编排", rendered.document().conflicts().get(0).positions().get(0).stance());
    }

    @Test
    void fallsBackToBulletListWhenModelReturnsNoProse() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle, "", "2026-11-11", Map.of());

        assertTrue(rendered.markdown().contains("## 核心结论"));
        assertTrue(rendered.markdown().contains("- A statement"));
        assertTrue(rendered.markdown().contains("[1]"));
    }

    @Test
    void referencesAreSortedByNumberWhenClaimOrderDiffersFromBodyOrder() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-2", "s2", "SUPPORTED", 0.8, citation("https://c.test/3")),
                claim("claim-1", "s1", "SUPPORTED", 0.9,
                        citation("https://a.test/1"), citation("https://b.test/2"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle,
                "First [[claim-1]]. Then [[claim-2]].", "2026-11-11", Map.of());

        assertEquals(List.of(1, 2, 3), rendered.document().references().stream()
                .map(ReportDocument.Reference::n).toList());
        assertEquals("https://a.test/1", rendered.document().references().get(0).url());
        assertEquals("https://b.test/2", rendered.document().references().get(1).url());
        assertEquals("https://c.test/3", rendered.document().references().get(2).url());
    }

    @Test
    void statsCarryTaskWarningsIntoTheStructuredDocument() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle, "Body [[claim-1]].",
                "2026-11-11", Map.of());

        assertTrue(rendered.document().stats().warnings().contains("EXTRACT_EMPTY: https://x.test"),
                "task-level warnings must survive into report stats (eval I8)");
    }

    @Test
    void includesRunStatisticsAssembledByCode() {
        ReportBundle bundle = bundle(List.of(
                claim("claim-1", "s1", "SUPPORTED", 0.9, citation("https://a.test/1"))));

        ReportRenderer.Rendered rendered = renderer.render(bundle, "Body [[claim-1]].", "2026-11-11", Map.of());

        ReportDocument.Stats stats = rendered.document().stats();
        assertEquals("COVERAGE_REACHED", stats.stopReason());
        assertEquals(6, stats.sourcesFetched());
        assertEquals(18, stats.evidence());
        assertEquals(1, stats.claims());
        assertEquals(1.0, stats.verifiedRatio());
        assertTrue(rendered.markdown().contains("## 方法与执行统计"));
        assertTrue(rendered.markdown().contains("token 用量：11300"));
    }

    private static ReportBundle bundle(List<ReportClaim> claims) {
        return new ReportBundle(
                "task-1",
                "Temporal 和 LangGraph 在长任务编排上的取舍？",
                "QUICK",
                "COVERAGE_REACHED",
                List.of("EXTRACT_EMPTY: https://x.test"),
                List.of(new ReportSection("s1", 1, "长任务编排", "Long-running orchestration?",
                                "COVERED", 0.9),
                        new ReportSection("s2", 2, "取舍", "Trade-offs?", "PARTIAL", 0.6)),
                claims,
                List.of(),
                new ReportStats(6, 1, 18, claims.size(),
                        (int) claims.stream().filter(ReportClaim::renderable).count(),
                        11_300, 33_600, 16_000));
    }

    private static ReportClaim claim(String id, String sectionKey, String status, double confidence,
                                     ReportCitation... citations) {
        return new ReportClaim(id, sectionKey, "A statement", 3, status, confidence,
                List.of(citations));
    }

    private static ReportCitation citation(String url) {
        return new ReportCitation("ev-" + url.hashCode(), "src-1", url, "Title of " + url,
                "a.test", "A verbatim quote from the source.", 0, 32);
    }
}
