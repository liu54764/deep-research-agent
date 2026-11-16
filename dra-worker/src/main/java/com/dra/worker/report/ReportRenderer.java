package com.dra.worker.report;

import com.dra.contracts.dto.ReportDocument;
import com.dra.domain.model.ReportBundle;
import com.dra.domain.model.ReportCitation;
import com.dra.domain.model.ReportClaim;
import com.dra.domain.model.ReportConflict;
import com.dra.domain.model.ReportPosition;
import com.dra.domain.model.ReportSection;
import com.dra.domain.model.ReportStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, fail-closed report renderer.
 *
 * <p>The model only writes prose with {@code [[claimId]]} markers. Everything that carries
 * factual or methodological weight is assembled here: unknown markers are stripped (and
 * reported), reference numbers are allocated server-side and de-duplicated by URL, and the
 * statistics / methodology / conflicts / rejected-claims sections are never model-generated.
 */
public final class ReportRenderer {

    private static final Pattern MARKER = Pattern.compile("\\[\\[\\s*([0-9A-Za-z_-]{1,64})\\s*]]");
    private static final int MAX_STRIPPED_WARNINGS = 5;

    public record Rendered(
            String markdown,
            ReportDocument document,
            List<String> warnings,
            int strippedMarkers) {
    }

    /**
     * @param modelMarkdown model prose containing {@code [[claimId]]} markers; may be blank
     * @param accessedAt     reference access time, passed in so this class stays clock-free
     */
    public Rendered render(ReportBundle bundle, String modelMarkdown, String accessedAt,
                           Map<String, String> meta) {
        List<String> warnings = new ArrayList<>();
        boolean chinese = isChinese(bundle.question());
        Map<String, ReportClaim> allowed = new LinkedHashMap<>();
        for (ReportClaim claim : bundle.renderableClaims()) {
            allowed.put(claim.id(), claim);
        }

        Map<String, Integer> urlToRef = new LinkedHashMap<>();
        Map<String, String> claimRefs = new HashMap<>();
        int[] stripped = {0};
        String body = replaceMarkers(modelMarkdown == null ? "" : modelMarkdown, allowed,
                urlToRef, claimRefs, warnings, stripped);

        if (allowed.isEmpty()) {
            body = (chinese
                    ? "本次研究未形成通过校验的可引用结论。请查看下方「未采纳结论」与「局限」了解原因。"
                    : "This run produced no verifiable claims. See the rejected claims and "
                    + "limitations sections below.");
            warnings.add("REPORT_NO_VERIFIED_CLAIMS");
        } else if (body.isBlank()) {
            body = fallbackBody(allowed, claimRefs, urlToRef, chinese);
        }

        List<ReportDocument.Reference> references = references(urlToRef, allowed, accessedAt);
        String markdown = assemble(bundle, body.trim(), references, chinese, warnings);

        ReportDocument document = new ReportDocument(
                sections(bundle, allowed, urlToRef, chinese),
                conflicts(bundle, allowed),
                references,
                stats(bundle.stats(), bundle.stopReason(), warnings, bundle.warnings()),
                meta == null ? Map.of() : meta);
        return new Rendered(markdown, document, warnings, stripped[0]);
    }

    private String replaceMarkers(String markdown, Map<String, ReportClaim> allowed,
                                  Map<String, Integer> urlToRef, Map<String, String> claimRefs,
                                  List<String> warnings, int[] stripped) {
        Matcher matcher = MARKER.matcher(markdown);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        int last = 0;
        while (matcher.find()) {
            out.append(markdown, last, matcher.start());
            String claimId = matcher.group(1);
            ReportClaim claim = allowed.get(claimId);
            if (claim == null) {
                stripped[0]++;
                if (countStripped(warnings) < MAX_STRIPPED_WARNINGS) {
                    warnings.add("REPORT_MARKER_STRIPPED:" + claimId);
                }
            } else {
                out.append(claimRefs.computeIfAbsent(claimId,
                        id -> refsFor(claim, urlToRef, warnings)));
            }
            last = matcher.end();
        }
        out.append(markdown.substring(last));
        return out.toString();
    }

    private int countStripped(List<String> warnings) {
        return (int) warnings.stream().filter(w -> w.startsWith("REPORT_MARKER_STRIPPED")).count();
    }

    private String refsFor(ReportClaim claim, Map<String, Integer> urlToRef, List<String> warnings) {
        StringBuilder refs = new StringBuilder();
        Set<Integer> seen = new LinkedHashSet<>();
        for (ReportCitation citation : claim.citations()) {
            String url = citation.url();
            if (url == null || url.isBlank()) {
                continue;
            }
            int n = urlToRef.computeIfAbsent(url, key -> urlToRef.size() + 1);
            if (seen.add(n)) {
                refs.append('[').append(n).append(']');
            }
        }
        if (refs.isEmpty()) {
            warnings.add("REPORT_CLAIM_WITHOUT_CITATION:" + claim.id());
        }
        return refs.toString();
    }

    private String fallbackBody(Map<String, ReportClaim> allowed, Map<String, String> claimRefs,
                                Map<String, Integer> urlToRef, boolean chinese) {
        StringBuilder body = new StringBuilder();
        body.append(chinese ? "## 核心结论\n\n" : "## Key findings\n\n");
        for (ReportClaim claim : allowed.values()) {
            String refs = claimRefs.computeIfAbsent(claim.id(), id -> refsFor(claim, urlToRef, new ArrayList<>()));
            body.append("- ").append(claim.statement());
            if (!refs.isEmpty()) {
                body.append(' ').append(refs);
            }
            body.append('\n');
        }
        return body.toString();
    }

    private List<ReportDocument.Reference> references(Map<String, Integer> urlToRef,
                                                      Map<String, ReportClaim> allowed,
                                                      String accessedAt) {
        Map<Integer, ReportDocument.Reference> byNumber = new LinkedHashMap<>();
        for (ReportClaim claim : allowed.values()) {
            for (ReportCitation citation : claim.citations()) {
                String url = citation.url();
                if (url == null || url.isBlank()) {
                    continue;
                }
                Integer n = urlToRef.get(url);
                if (n == null) {
                    continue;
                }
                byNumber.putIfAbsent(n, new ReportDocument.Reference(
                        n, url, nullToEmpty(citation.title()), accessedAt));
            }
        }
        return byNumber.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private List<ReportDocument.Section> sections(ReportBundle bundle, Map<String, ReportClaim> allowed,
                                                  Map<String, Integer> urlToRef, boolean chinese) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (ReportSection section : bundle.sections()) {
            titles.put(section.id(), section.title() == null || section.title().isBlank()
                    ? section.question()
                    : section.title());
        }
        Map<String, List<ReportDocument.Claim>> grouped = new LinkedHashMap<>();
        for (ReportClaim claim : allowed.values()) {
            String key = claim.sectionKey() == null || claim.sectionKey().isBlank()
                    ? (chinese ? "其他" : "Other")
                    : claim.sectionKey();
            grouped.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new ReportDocument.Claim(
                            claim.id(),
                            claim.statement(),
                            claim.status(),
                            round(claim.confidence()),
                            citations(claim, urlToRef)));
        }
        List<ReportDocument.Section> sections = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<ReportDocument.Claim>> entry : grouped.entrySet()) {
            String title = titles.get(entry.getKey());
            if (title == null || title.isBlank()) {
                // Model-chosen section keys (s1, overview, ...) are not user-facing titles.
                title = chinese ? "其他结论" : "Other findings";
            }
            sections.add(new ReportDocument.Section(entry.getKey(), title, entry.getValue()));
        }
        return sections;
    }

    private List<ReportDocument.Citation> citations(ReportClaim claim, Map<String, Integer> urlToRef) {
        Map<String, ReportDocument.Citation> byUrl = new LinkedHashMap<>();
        for (ReportCitation citation : claim.citations()) {
            String url = citation.url();
            if (url == null || url.isBlank()) {
                continue;
            }
            Integer n = urlToRef.get(url);
            String ref = n == null ? "" : "[" + n + "]";
            byUrl.putIfAbsent(url, new ReportDocument.Citation(
                    ref, url, nullToEmpty(citation.title()), nullToEmpty(citation.quote())));
        }
        return new ArrayList<>(byUrl.values());
    }

    private List<ReportDocument.Conflict> conflicts(ReportBundle bundle, Map<String, ReportClaim> allowed) {
        List<ReportDocument.Conflict> conflicts = new ArrayList<>();
        for (ReportConflict conflict : bundle.conflicts()) {
            List<ReportDocument.Position> positions = new ArrayList<>();
            for (ReportPosition position : conflict.positions()) {
                ReportClaim claim = allowed.get(position.claimId());
                positions.add(new ReportDocument.Position(
                        position.claimId(),
                        claim == null ? nullToEmpty(position.statement()) : claim.statement(),
                        nullToEmpty(position.stance()),
                        position.urls()));
            }
            conflicts.add(new ReportDocument.Conflict(
                    nullToEmpty(conflict.topic()),
                    nullToEmpty(conflict.summary()),
                    positions));
        }
        return conflicts;
    }

    private ReportDocument.Stats stats(ReportStats stats, String stopReason, List<String> warnings,
                                       List<String> bundleWarnings) {
        // Task-level warnings (fetch failures, budget stops) must survive into the report
        // stats, otherwise the eval I8 invariant cannot be satisfied in real runs.
        List<String> allWarnings = new ArrayList<>();
        for (String warning : bundleWarnings) {
            if (warning != null && !warning.isBlank() && !allWarnings.contains(warning)) {
                allWarnings.add(warning);
            }
        }
        for (String warning : warnings) {
            if (!allWarnings.contains(warning)) {
                allWarnings.add(warning);
            }
        }
        return new ReportDocument.Stats(
                nullToEmpty(stopReason), stats.durationMs(), stats.tokens(), stats.costMicros(),
                stats.sourcesFetched(), stats.evidenceCount(), stats.claimsTotal(),
                round(stats.verifiedRatio()), allWarnings);
    }

    private String assemble(ReportBundle bundle, String body, List<ReportDocument.Reference> references,
                            boolean chinese, List<String> warnings) {
        StringBuilder out = new StringBuilder();
        String title = nullToEmpty(bundle.question()).isBlank()
                ? (chinese ? "研究报告" : "Research Report")
                : bundle.question();
        out.append("# ").append(title).append("\n\n");
        out.append(body).append("\n\n");

        if (!references.isEmpty()) {
            out.append(chinese ? "## 参考来源\n\n" : "## References\n\n");
            for (ReportDocument.Reference reference : references) {
                out.append('[').append(reference.n()).append("] ")
                        .append(nullToEmpty(reference.title()).isBlank()
                                ? reference.url()
                                : reference.title() + " — " + reference.url())
                        .append('\n');
            }
            out.append('\n');
        }

        if (!bundle.conflicts().isEmpty()) {
            out.append(chinese ? "## 争议与反证\n\n" : "## Disagreements\n\n");
            out.append(chinese
                    ? "以下问题在不同来源之间存在分歧，报告并列呈现，不作裁决：\n\n"
                    : "Sources disagree on the following points; both sides are shown without adjudication:\n\n");
            Map<String, String> statements = new LinkedHashMap<>();
            for (ReportClaim claim : bundle.claims()) {
                statements.put(claim.id(), claim.statement());
            }
            for (ReportConflict conflict : bundle.conflicts()) {
                out.append("### ").append(nullToEmpty(conflict.topic())).append("\n\n");
                if (!conflict.summary().isBlank()) {
                    out.append(conflict.summary()).append("\n\n");
                }
                for (ReportPosition position : conflict.positions()) {
                    String statement = nullToEmpty(position.statement());
                    if (statement.isBlank()) {
                        statement = statements.getOrDefault(position.claimId(), "");
                    }
                    out.append("- ").append(statement.isBlank()
                                    ? position.claimId()
                                    : statement)
                            .append(chinese ? "（" : " (")
                            .append(shorten(position.stance(), 60))
                            .append(chinese ? "）" : ")");
                    if (!position.urls().isEmpty()) {
                        out.append(" ").append(String.join(" ", position.urls()));
                    }
                    out.append('\n');
                }
                out.append('\n');
            }
        }

        out.append(chinese ? "## 方法与执行统计\n\n" : "## Method & run statistics\n\n");
        ReportStats stats = bundle.stats();
        out.append(chinese ? "- 研究深度：" : "- Depth: ").append(nullToEmpty(bundle.depth())).append('\n');
        out.append(chinese ? "- 停止原因：" : "- Stop reason: ").append(nullToEmpty(bundle.stopReason())).append('\n');
        out.append(chinese ? "- 耗时：" : "- Duration: ").append(stats.durationMs() / 1000).append(chinese ? " 秒" : "s").append('\n');
        out.append(chinese ? "- 抓取来源：" : "- Sources fetched: ")
                .append(stats.sourcesFetched()).append(chinese ? "（失败 " : " (failed ")
                .append(stats.sourcesFailed()).append(chinese ? "）\n" : ")\n");
        out.append(chinese ? "- 证据条数：" : "- Evidence rows: ").append(stats.evidenceCount()).append('\n');
        out.append(chinese ? "- 结论条数：" : "- Claims: ").append(stats.claimsTotal())
                .append(chinese ? "，通过校验 " : ", verified ").append(stats.claimsVerified()).append('\n');
        out.append(chinese ? "- 校验通过率：" : "- Verified ratio: ")
                .append(String.format("%.0f%%", stats.verifiedRatio() * 100)).append('\n');
        out.append(chinese ? "- token 用量：" : "- Tokens: ").append(stats.tokens()).append('\n');
        out.append(chinese ? "- 成本（微元）：" : "- Cost (micro): ").append(stats.costMicros()).append("\n\n");

        List<ReportClaim> rejected = bundle.rejectedClaims();
        if (!rejected.isEmpty() || !warnings.isEmpty() || !bundle.warnings().isEmpty()) {
            out.append(chinese ? "## 局限与未采纳结论\n\n" : "## Limitations & rejected claims\n\n");
            out.append(chinese
                    ? "- 本报告只呈现通过规则 + 模型双通道校验的结论；未通过校验的结论不作为事实。\n"
                    : "- Only claims passing rule + model verification are presented as facts.\n");
            for (ReportClaim claim : rejected) {
                out.append("- [").append(claim.status()).append("] ")
                        .append(claim.statement()).append('\n');
            }
            for (String warning : bundle.warnings()) {
                out.append("- ").append(chinese ? "运行告警：" : "warning: ").append(warning).append('\n');
            }
            long stripped = warnings.stream()
                    .filter(warning -> warning.startsWith("REPORT_MARKER_STRIPPED"))
                    .count();
            if (stripped > 0) {
                out.append("- ").append(chinese
                                ? stripped + " 处引用标记无法解析，已删除"
                                : stripped + " unresolved citation marker(s) were stripped")
                        .append('\n');
            }
            for (String warning : warnings) {
                if (warning.startsWith("REPORT_MARKER_STRIPPED")
                        || warning.startsWith("REPORT_CLAIM_WITHOUT_CITATION")) {
                    continue;
                }
                out.append("- ").append(chinese ? "渲染告警：" : "render warning: ")
                        .append(warning).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static boolean isChinese(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String shorten(String value, int max) {
        String text = nullToEmpty(value);
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
