package com.dra.eval.score.rule;

import com.dra.contracts.dto.ReportDocument;
import com.dra.contracts.model.Depth;
import com.dra.contracts.model.TaskStatus;
import com.dra.domain.model.SourceContent;
import com.dra.domain.model.TaskRow;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.model.FetchedSource;
import com.dra.eval.collect.DbSnapshot;
import com.dra.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleScorerTest {

    private static final String URL = "https://example.com/post";
    private static final String CONTENT = "Some intro. The quick brown fox jumps over the lazy dog. Outro.";

    private final SourceRepository sources = new SourceRepository() {
        @Override
        public void save(FetchedSource source) {
        }

        @Override
        public Optional<SourceContent> findContent(String sourceId) {
            return Optional.of(new SourceContent(sourceId, URL, "title", CONTENT));
        }
    };

    private final RuleScorer scorer = new RuleScorer(sources, new EvalProperties());

    @Test
    void countsAnchoredFullTextCitation() {
        RuleScorer.RuleResult result = scorer.score(
                document("quick brown fox"), snapshot("FULL_TEXT", URL), false);

        assertEquals(1, result.renderedCitations());
        assertEquals(1, result.fullTextCitations());
        assertEquals(1, result.anchored());
        assertEquals(1.0, result.anchorRate());
        assertEquals(0, result.unresolved());
    }

    @Test
    void countsQuoteMissingFromSourceAsNotAnchored() {
        RuleScorer.RuleResult result = scorer.score(
                document("this sentence is made up"),
                snapshot("FULL_TEXT", URL, "this sentence is made up"), false);

        assertEquals(1, result.fullTextCitations());
        assertEquals(0, result.anchored());
        assertEquals(0.0, result.anchorRate());
    }

    @Test
    void snippetEvidenceNeverCountsAsAnchored() {
        RuleScorer.RuleResult result = scorer.score(
                document("quick brown fox"), snapshot("SEARCH_SNIPPET", URL), false);

        assertEquals(0, result.fullTextCitations());
        assertEquals(1, result.snippetCitations());
        assertEquals(0.0, result.anchorRate());
    }

    @Test
    void citationThatDoesNotResolveIsCountedAsUnresolved() {
        RuleScorer.RuleResult result = scorer.score(
                document("quick brown fox"), snapshot("FULL_TEXT", "https://other.example.com/x"), false);

        assertEquals(1, result.renderedCitations());
        assertEquals(0, result.resolvedInDb());
        assertEquals(1, result.unresolved());
    }

    @Test
    void urlCheckIsSkippedWhenDisabled() {
        RuleScorer.RuleResult result = scorer.score(document("quick brown fox"),
                snapshot("FULL_TEXT", URL), false);

        assertEquals(0, result.urls().sampled());
        assertEquals(0.0, result.urls().liveRate());
    }

    @Test
    void hangingUrlCheckCannotBlockTheEvaluator() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "slow".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";
        try {
            EvalProperties properties = new EvalProperties();
            properties.getUrlCheck().setEnabled(true);
            properties.getUrlCheck().setTimeoutSeconds(1);
            properties.getUrlCheck().setSampleLimit(1);
            RuleScorer scorer = new RuleScorer(sources, properties);

            long start = System.nanoTime();
            RuleScorer.RuleResult result = scorer.score(document(url, "quick brown fox"),
                    snapshot("FULL_TEXT", url, "quick brown fox"), true);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(1, result.urls().sampled());
            assertEquals(1, result.urls().unverifiable());
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 3_000,
                    "url check must time out, took " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    private static ReportDocument document(String quote) {
        return document(URL, quote);
    }

    private static ReportDocument document(String url, String quote) {
        return new ReportDocument(
                List.of(new ReportDocument.Section("s1", "Section", List.of(
                        new ReportDocument.Claim("c1", "the statement", "SUPPORTED", 0.8,
                                List.of(new ReportDocument.Citation("[1]", url, "title", quote)))))),
                List.of(),
                List.of(new ReportDocument.Reference(1, url, "title", "2026-11-15")),
                new ReportDocument.Stats("COVERAGE_REACHED", 1000, 100, 10, 1, 1, 1, 1.0, List.of()),
                Map.of("stopReason", "COVERAGE_REACHED"));
    }

    private static DbSnapshot snapshot(String origin, String sourceUrl) {
        return snapshot(origin, sourceUrl, "quick brown fox");
    }

    private static DbSnapshot snapshot(String origin, String sourceUrl, String quote) {
        TaskRow task = new TaskRow("t1", "idem", "hash", "question", TaskStatus.SUCCEEDED,
                Depth.QUICK, 1000, 0, 100, 10, "wf", "run", "COVERAGE_REACHED",
                null, null, "r1", 0L, 0L, 1L);
        return new DbSnapshot(
                task,
                List.of(new DbSnapshot.DbClaim("c1", "s1", "the statement", 1, "SUPPORTED", 0.8)),
                List.of(new DbSnapshot.DbCitation("c1", "e1", "src1", sourceUrl, "title",
                        quote, origin)),
                List.of(new DbSnapshot.DbSource("src1", sourceUrl, sourceUrl, "OK")),
                1, 100, 10);
    }
}
