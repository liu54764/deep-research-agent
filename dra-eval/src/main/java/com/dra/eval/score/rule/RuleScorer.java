package com.dra.eval.score.rule;

import com.dra.contracts.dto.ReportDocument;
import com.dra.domain.model.SourceContent;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.util.QuoteValidator;
import com.dra.domain.util.UrlCanonicalizer;
import com.dra.eval.collect.DbSnapshot;
import com.dra.eval.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic half of the scoring protocol: anchor hit rate over stored source content,
 * plus optional URL liveness sampling. No model calls here.
 */
@Component
public class RuleScorer {

    private static final Logger log = LoggerFactory.getLogger(RuleScorer.class);
    private static final Set<Integer> UNVERIFIABLE_STATUS = Set.of(401, 403, 405, 429);

    private final SourceRepository sourceRepository;
    private final EvalProperties properties;
    private final HttpClient httpClient;

    public RuleScorer(SourceRepository sourceRepository, EvalProperties properties) {
        this.sourceRepository = sourceRepository;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getUrlCheck().getTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public RuleResult score(ReportDocument document, DbSnapshot snapshot, boolean urlCheckEnabled) {
        Map<String, List<DbSnapshot.DbCitation>> byClaim = new HashMap<>();
        for (DbSnapshot.DbCitation citation : snapshot.citations()) {
            byClaim.computeIfAbsent(citation.claimId(), id -> new ArrayList<>()).add(citation);
        }
        Map<String, Optional<SourceContent>> contentCache = new HashMap<>();
        Set<String> urls = new LinkedHashSet<>();

        int rendered = 0;
        int resolved = 0;
        int fullText = 0;
        int anchored = 0;
        int snippets = 0;

        for (ReportDocument.Section section : document.sections()) {
            for (ReportDocument.Claim claim : section.claims()) {
                List<DbSnapshot.DbCitation> dbCitations = byClaim.getOrDefault(claim.id(), List.of());
                for (ReportDocument.Citation citation : claim.citations()) {
                    rendered++;
                    if (citation.url() != null && !citation.url().isBlank()) {
                        urls.add(citation.url());
                    }
                    Optional<DbSnapshot.DbCitation> match = match(claim.id(), citation, dbCitations);
                    if (match.isEmpty()) {
                        continue;
                    }
                    resolved++;
                    DbSnapshot.DbCitation dbCitation = match.get();
                    if (!"FULL_TEXT".equals(dbCitation.origin())) {
                        snippets++;
                        continue;
                    }
                    fullText++;
                    Optional<SourceContent> content = contentCache.computeIfAbsent(
                            dbCitation.sourceId(), sourceRepository::findContent);
                    if (content.isPresent()
                            && QuoteValidator.isSupportedBy(dbCitation.quote(), content.get().content())) {
                        anchored++;
                    }
                }
            }
        }

        UrlResult urlResult = urlCheckEnabled
                ? checkUrls(new ArrayList<>(urls))
                : UrlResult.SKIPPED;
        double anchorRate = fullText == 0 ? 0.0 : (double) anchored / fullText;
        return new RuleResult(rendered, resolved, fullText, anchored, snippets,
                rendered - resolved, anchorRate, urlResult);
    }

    private Optional<DbSnapshot.DbCitation> match(String claimId, ReportDocument.Citation citation,
                                                  List<DbSnapshot.DbCitation> dbCitations) {
        String url = canonical(citation.url());
        String quote = citation.quote() == null ? "" : citation.quote().trim();
        for (DbSnapshot.DbCitation dbCitation : dbCitations) {
            if (!claimId.equals(dbCitation.claimId()) || !url.equals(canonical(dbCitation.url()))) {
                continue;
            }
            String dbQuote = dbCitation.quote() == null ? "" : dbCitation.quote().trim();
            if (quote.isEmpty() || quote.equals(dbQuote)) {
                return Optional.of(dbCitation);
            }
        }
        return Optional.empty();
    }

    private UrlResult checkUrls(List<String> urls) {
        int limit = Math.max(1, properties.getUrlCheck().getSampleLimit());
        List<String> sample = urls.stream().distinct().limit(limit).toList();
        if (sample.isEmpty()) {
            return new UrlResult(0, 0, 0, 0, 0.0);
        }
        List<CompletableFuture<Integer>> futures = sample.stream()
                .map(this::probe)
                .toList();
        int live = 0;
        int dead = 0;
        int unverifiable = 0;
        for (CompletableFuture<Integer> future : futures) {
            int status = future.join();
            if (status >= 200 && status < 400) {
                live++;
            } else if (status == 404 || status == 410) {
                dead++;
            } else {
                unverifiable++;
            }
        }
        int checkable = live + dead;
        return new UrlResult(sample.size(), live, dead, unverifiable,
                checkable == 0 ? 0.0 : (double) live / checkable);
    }

    /** Returns the HTTP status, or -1 when the probe could not reach the host. */
    private CompletableFuture<Integer> probe(String url) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(properties.getUrlCheck().getTimeoutSeconds()))
                    .header("User-Agent", "DeepResearchAgent-Eval/0.1")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            log.debug("skip malformed url {}", url);
            return CompletableFuture.completedFuture(-1);
        }
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                // HttpRequest.timeout does not bound a stuck DNS/TLS handshake; orTimeout
                // guarantees the evaluator can never hang on one bad citation URL.
                .orTimeout(properties.getUrlCheck().getTimeoutSeconds(), TimeUnit.SECONDS)
                .handle((response, error) -> {
                    if (error != null) {
                        log.debug("url probe failed {}: {}", url, error.toString());
                        return -1;
                    }
                    return UNVERIFIABLE_STATUS.contains(response.statusCode())
                            ? -1 : response.statusCode();
                });
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

    public record RuleResult(
            int renderedCitations,
            int resolvedInDb,
            int fullTextCitations,
            int anchored,
            int snippetCitations,
            int unresolved,
            double anchorRate,
            UrlResult urls) {

        public static RuleResult empty() {
            return new RuleResult(0, 0, 0, 0, 0, 0, 0.0, UrlResult.SKIPPED);
        }
    }

    public record UrlResult(
            int sampled,
            int live,
            int dead,
            int unverifiable,
            double liveRate) {

        public static final UrlResult SKIPPED = new UrlResult(0, 0, 0, 0, 0.0);

        public boolean skipped() {
            return sampled == 0;
        }
    }
}
