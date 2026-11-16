package com.dra.worker.activity;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ReportDocument;
import com.dra.contracts.dto.ReportInput;
import com.dra.contracts.dto.ReportRef;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ReportBundle;
import com.dra.domain.model.ReportCitation;
import com.dra.domain.model.ReportClaim;
import com.dra.domain.model.ReportConflict;
import com.dra.domain.model.ReportSection;
import com.dra.domain.port.ReportDataRepository;
import com.dra.domain.port.ReportRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.dra.worker.report.ReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates the report. The model writes prose only; markers, citation numbering,
 * statistics, methodology, conflicts and rejected claims are rendered by
 * {@link ReportRenderer}. A model that references claims not in the input is repaired
 * once and then fail-closed: the marker is stripped and the run records a warning.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class ReportActivitiesImpl implements ResearchActivities.Report {

    private static final Logger log = LoggerFactory.getLogger(ReportActivitiesImpl.class);
    private static final String PROMPT_VERSION = "report.v1";
    private static final int MAX_CLAIMS = 24;
    private static final int MAX_REPAIRS = 1;

    private final LlmPort llm;
    private final ReportDataRepository reportData;
    private final ReportRepository reportRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final ReportRenderer renderer = new ReportRenderer();

    public ReportActivitiesImpl(LlmPort llm, ReportDataRepository reportData,
                                ReportRepository reportRepository, UsageRepository usageRepository,
                                PromptLoader promptLoader, LlmProperties properties,
                                ObjectMapper objectMapper) {
        this.llm = llm;
        this.reportData = reportData;
        this.reportRepository = reportRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportRef generate(ReportInput input) {
        ReportBundle bundle = reportData.load(input.taskId(), input.stopReason(), input.warnings());
        List<ReportClaim> renderable = bundle.renderableClaims().stream()
                .limit(MAX_CLAIMS)
                .toList();
        String accessedAt = LocalDate.now().toString();
        Map<String, String> meta = meta(bundle);

        Usage usage = Usage.ZERO;
        String prose = "";
        if (!renderable.isEmpty()) {
            String system = promptLoader.load("prompts/report.v1.system.md");
            String user = promptLoader.render(promptLoader.load("prompts/report.v1.user.md"), Map.of(
                    "question", nullToEmpty(bundle.question()),
                    "depth", nullToEmpty(bundle.depth()),
                    "sections", formatSections(bundle, renderable),
                    "conflicts", formatConflicts(bundle.conflicts())));
            try {
                LlmResponse response = call(input.taskId(), system, user);
                usage = usage.plus(response.usage());
                usageRepository.record(input.taskId(), "report.generate",
                        properties.getClaimModel(), response.usage());
                prose = stripCodeFence(response.content());
                ReportRenderer.Rendered rendered = renderer.render(bundle, prose, accessedAt, meta);
                int repair = 0;
                while (rendered.strippedMarkers() > 0 && repair < MAX_REPAIRS) {
                    repair++;
                    LlmResponse fix = call(input.taskId(), system,
                            user + "\n\n上一次输出包含未给出的 claimId 标记（已删除）："
                                    + rendered.warnings() + "\n请只使用上面列出的 claimId，重新输出全部正文。");
                    usage = usage.plus(fix.usage());
                    usageRepository.record(input.taskId(), "report.repair",
                            properties.getClaimModel(), fix.usage());
                    prose = stripCodeFence(fix.content());
                    rendered = renderer.render(bundle, prose, accessedAt, meta);
                }
                if (rendered.strippedMarkers() > 0) {
                    log.warn("report markers stripped after repair for task {}: {}",
                            input.taskId(), rendered.strippedMarkers());
                }
                return persist(input, rendered, usage);
            } catch (RuntimeException e) {
                log.warn("LLM unavailable for report, rendering deterministic body: {}",
                        e.getMessage());
            }
        }
        ReportRenderer.Rendered rendered = renderer.render(bundle, "", accessedAt, meta);
        return persist(input, rendered, usage);
    }

    private ReportRef persist(ReportInput input, ReportRenderer.Rendered rendered, Usage usage) {
        String metaJson = toJson(rendered.document());
        int version = reportRepository.findLatest(input.taskId())
                .map(row -> row.version() + 1)
                .orElse(1);
        String reportId = Ids.ulid();
        var row = reportRepository.save(reportId, input.taskId(), version,
                rendered.markdown(), metaJson);
        return new ReportRef(row.id(), row.version(), row.markdown(), usage);
    }

    private LlmResponse call(String taskId, String system, String user) {
        return llm.complete(new LlmRequest(taskId, "report.generate", properties.getClaimModel(),
                system, user, 0.2, properties.getMaxTokens()));
    }

    private static String formatSections(ReportBundle bundle, List<ReportClaim> claims) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (ReportSection section : bundle.sections()) {
            titles.put(section.id(), section.title() == null ? section.question() : section.title());
        }
        Map<String, List<ReportClaim>> grouped = claims.stream()
                .collect(Collectors.groupingBy(
                        claim -> claim.sectionKey() == null || claim.sectionKey().isBlank()
                                ? "other" : claim.sectionKey(),
                        LinkedHashMap::new, Collectors.toList()));
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<ReportClaim>> entry : grouped.entrySet()) {
            text.append("## ").append(titles.getOrDefault(entry.getKey(), entry.getKey()))
                    .append(" (sectionKey=").append(entry.getKey()).append(")\n");
            for (ReportClaim claim : entry.getValue()) {
                text.append("- claimId=").append(claim.id())
                        .append(" status=").append(claim.status())
                        .append(" confidence=").append(claim.confidence())
                        .append("\n  statement: ").append(claim.statement()).append('\n');
                for (ReportCitation citation : claim.citations().stream().limit(3).toList()) {
                    text.append("  evidence: url=").append(nullToEmpty(citation.url()))
                            .append(" quote=\"").append(truncate(nullToEmpty(citation.quote()), 300))
                            .append("\"\n");
                }
            }
        }
        return text.toString();
    }

    private static String formatConflicts(List<ReportConflict> conflicts) {
        if (conflicts.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        for (ReportConflict conflict : conflicts) {
            text.append("- topic=").append(conflict.topic())
                    .append(" summary=").append(conflict.summary()).append('\n');
        }
        return text.toString();
    }

    private Map<String, String> meta(ReportBundle bundle) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("depth", nullToEmpty(bundle.depth()));
        meta.put("stopReason", nullToEmpty(bundle.stopReason()));
        meta.put("question", nullToEmpty(bundle.question()));
        meta.put("promptVersions", "plan.v1,extract.v1,claim.v1,verify.v1,conflict.v1,report.v1");
        meta.put("models", String.join(",", List.of(
                properties.getPlanModel(), properties.getExtractModel(),
                properties.getClaimModel(), properties.getVerifyModel())));
        return meta;
    }

    private String toJson(ReportDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (Exception e) {
            log.warn("cannot serialize report document: {}", e.getMessage());
            return "{}";
        }
    }

    private static String stripCodeFence(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return stripLeadingHeading(trimmed.trim());
    }

    /** The renderer owns the document title, so a model-written leading H1 would duplicate it. */
    private static String stripLeadingHeading(String value) {
        String result = value;
        while (result.startsWith("# ")) {
            int newline = result.indexOf('\n');
            if (newline < 0) {
                return "";
            }
            result = result.substring(newline + 1).trim();
        }
        return result;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
