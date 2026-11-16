package com.dra.worker.activity;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ConflictDraft;
import com.dra.contracts.dto.ConflictInput;
import com.dra.contracts.dto.ConflictPosition;
import com.dra.contracts.dto.ConflictReport;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.port.CitationRepository;
import com.dra.domain.port.ClaimRepository;
import com.dra.domain.port.ConflictRepository;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.config.ConfidenceProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects disagreements between verified claims. The model may only pair claims and
 * describe a stance — every claim ID and URL it references is validated against the
 * citations actually stored for that claim, otherwise the position is dropped.
 * The agent never decides who is right; the report shows both sides.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class ConflictActivitiesImpl implements ResearchActivities.Conflict {

    private static final Logger log = LoggerFactory.getLogger(ConflictActivitiesImpl.class);
    private static final String PROMPT_VERSION = "conflict.v1";
    // Conflict detection used to see only the first 20 claims; with atomic claims that
    // is barely half the set, so disagreements further down were never considered.
    private static final int MAX_CLAIMS = 30;
    private static final int MAX_CONFLICTS = 5;
    private static final Set<String> VERDICTS = Set.of("SUPPORTED", "PARTIAL");

    private final LlmPort llm;
    private final ClaimRepository claimRepository;
    private final CitationRepository citationRepository;
    private final EvidenceRepository evidenceRepository;
    private final ConflictRepository conflictRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ConfidenceProperties confidenceProperties;
    private final ObjectMapper objectMapper;

    public ConflictActivitiesImpl(LlmPort llm, ClaimRepository claimRepository,
                                  CitationRepository citationRepository,
                                  EvidenceRepository evidenceRepository,
                                  ConflictRepository conflictRepository,
                                  UsageRepository usageRepository, PromptLoader promptLoader,
                                  LlmProperties properties, ConfidenceProperties confidenceProperties,
                                  ObjectMapper objectMapper) {
        this.llm = llm;
        this.claimRepository = claimRepository;
        this.citationRepository = citationRepository;
        this.evidenceRepository = evidenceRepository;
        this.conflictRepository = conflictRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.confidenceProperties = confidenceProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConflictReport detect(ConflictInput input) {
        List<ClaimRow> claims = new ArrayList<>();
        for (String claimId : input.claimIds().stream().limit(MAX_CLAIMS).toList()) {
            Optional<ClaimRow> claim = claimRepository.findById(claimId);
            if (claim.isPresent() && VERDICTS.contains(nullToEmpty(claim.get().status()))) {
                claims.add(claim.get());
            }
        }
        if (claims.size() < 2) {
            return ConflictReport.empty();
        }

        Map<String, List<EvidenceView>> evidenceByClaim = claims.stream()
                .collect(Collectors.toMap(ClaimRow::id, claim -> loadEvidence(claim)));
        String system = promptLoader.load("prompts/conflict.v1.system.md");
        String user = promptLoader.render(promptLoader.load("prompts/conflict.v1.user.md"), Map.of(
                "claims", formatClaims(claims, evidenceByClaim)));

        Usage usage = Usage.ZERO;
        List<ConflictDraft> drafts;
        try {
            LlmResponse response = llm.complete(new LlmRequest(input.taskId(), "conflict.detect",
                    properties.getClaimModel(), system, user, 0.0, properties.getMaxTokens()));
            usage = response.usage();
            usageRepository.record(input.taskId(), "conflict.detect",
                    properties.getClaimModel(), usage);
            drafts = parse(response.content(), claims, evidenceByClaim);
        } catch (RuntimeException e) {
            log.warn("conflict detection failed, continuing without conflicts: {}", e.getMessage());
            return new ConflictReport(List.of(), usage);
        }

        List<ConflictDraft> persisted = new ArrayList<>(drafts.size());
        for (ConflictDraft draft : drafts) {
            String conflictId = Ids.stableId(input.taskId(), PROMPT_VERSION, draft.topic());
            conflictRepository.save(conflictId, input.taskId(), truncate(draft.topic(), 128),
                    draft.summary(), positionsJson(draft));
            persisted.add(draft);
        }
        if (!persisted.isEmpty()) {
            applyConflictPenalty(persisted);
            log.info("conflict detection found {} disagreements for task {}",
                    persisted.size(), input.taskId());
        }
        return new ConflictReport(persisted, usage);
    }

    /**
     * M8 left conflicts out of the confidence formula. Subtract a bounded penalty once per
     * claim involved, so the report (generated after this activity) shows the lower value.
     */
    private void applyConflictPenalty(List<ConflictDraft> drafts) {
        Map<String, Integer> conflictsByClaim = new java.util.LinkedHashMap<>();
        for (ConflictDraft draft : drafts) {
            for (ConflictPosition position : draft.positions()) {
                conflictsByClaim.merge(position.claimId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> entry : conflictsByClaim.entrySet()) {
            claimRepository.findById(entry.getKey()).ifPresent(claim -> {
                int penalized = Math.min(entry.getValue(),
                        Math.max(0, confidenceProperties.getMaxPenalizedConflicts()));
                double penalty = confidenceProperties.getConflictPenalty() * penalized;
                double updated = Math.max(0.0, claim.confidence() - penalty);
                if (updated < claim.confidence()) {
                    claimRepository.updateConfidence(claim.id(), updated);
                    log.info("claim {} confidence {} -> {} ({} conflicts)",
                            claim.id(), String.format(java.util.Locale.ROOT, "%.3f", claim.confidence()),
                            String.format(java.util.Locale.ROOT, "%.3f", updated), entry.getValue());
                }
            });
        }
    }

    private List<EvidenceView> loadEvidence(ClaimRow claim) {
        List<String> evidenceIds = citationRepository.findEvidenceIds(claim.id());
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        return evidenceRepository.findByIds(claim.taskId(), evidenceIds);
    }

    private String positionsJson(ConflictDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft.positions());
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<ConflictDraft> parse(String raw, List<ClaimRow> claims,
                                      Map<String, List<EvidenceView>> evidenceByClaim) {
        JsonNode root;
        try {
            String json = extractJson(raw);
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("conflict JSON invalid: {}", e.getMessage());
            return List.of();
        }
        JsonNode array = root.path("conflicts");
        if (!array.isArray()) {
            return List.of();
        }
        Set<String> knownClaims = claims.stream().map(ClaimRow::id).collect(Collectors.toSet());
        List<ConflictDraft> drafts = new ArrayList<>();
        for (JsonNode node : array) {
            if (drafts.size() >= MAX_CONFLICTS) {
                break;
            }
            String topic = node.path("topic").asText("").trim();
            String summary = node.path("summary").asText("").trim();
            if (topic.isBlank()) {
                continue;
            }
            List<ConflictPosition> positions = new ArrayList<>();
            for (JsonNode position : node.path("positions")) {
                String claimId = position.path("claimId").asText("").trim();
                if (!knownClaims.contains(claimId)) {
                    continue;
                }
                Set<String> allowedUrls = evidenceByClaim.getOrDefault(claimId, List.of()).stream()
                        .map(EvidenceView::sourceUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                List<String> urls = new ArrayList<>();
                for (JsonNode url : position.path("urls")) {
                    String value = url.asText("").trim();
                    if (allowedUrls.contains(value) && !urls.contains(value)) {
                        urls.add(value);
                    }
                }
                positions.add(new ConflictPosition(claimId,
                        position.path("stance").asText("").trim(), urls));
            }
            if (positions.size() < 2) {
                continue;
            }
            drafts.add(new ConflictDraft(topic, summary, positions));
        }
        return drafts;
    }

    private static String formatClaims(List<ClaimRow> claims,
                                       Map<String, List<EvidenceView>> evidenceByClaim) {
        StringBuilder text = new StringBuilder();
        for (ClaimRow claim : claims) {
            text.append("- claimId=").append(claim.id())
                    .append(" statement=").append(claim.statement())
                    .append(" status=").append(nullToEmpty(claim.status()))
                    .append('\n');
            for (EvidenceView evidence : evidenceByClaim.getOrDefault(claim.id(), List.of())) {
                text.append("    - url=").append(nullToEmpty(evidence.sourceUrl()))
                        .append(" quote=\"").append(truncate(nullToEmpty(evidence.quote()), 300))
                        .append("\"\n");
            }
        }
        return text.toString();
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object found");
        }
        return raw.substring(start, end + 1);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
