package com.dra.worker.activity;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ClaimDraft;
import com.dra.contracts.dto.SynthesizeInput;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.port.CitationRepository;
import com.dra.domain.port.ClaimRepository;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Synthesizes atomic claims from evidence. A claim without at least one valid evidenceId
 * is discarded — the citation model is the contract that makes reports verifiable.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class ClaimActivitiesImpl implements ResearchActivities.Claim {

    private static final Logger log = LoggerFactory.getLogger(ClaimActivitiesImpl.class);
    private static final String PROMPT_VERSION = "claim.v1";
    private static final int MAX_CLAIMS = 30;
    private static final int MAX_EVIDENCE_IN_PROMPT = 40;

    private final LlmPort llm;
    private final EvidenceRepository evidenceRepository;
    private final ClaimRepository claimRepository;
    private final CitationRepository citationRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public ClaimActivitiesImpl(LlmPort llm, EvidenceRepository evidenceRepository,
                               ClaimRepository claimRepository, CitationRepository citationRepository,
                               UsageRepository usageRepository, PromptLoader promptLoader,
                               LlmProperties properties, ObjectMapper objectMapper) {
        this.llm = llm;
        this.evidenceRepository = evidenceRepository;
        this.claimRepository = claimRepository;
        this.citationRepository = citationRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ClaimDraft> synthesize(SynthesizeInput input) {
        List<EvidenceView> allEvidence = evidenceRepository.findByIds(input.taskId(), input.evidenceIds());
        if (allEvidence.isEmpty()) {
            return List.of();
        }
        List<EvidenceView> evidence = allEvidence.size() > MAX_EVIDENCE_IN_PROMPT
                ? allEvidence.subList(0, MAX_EVIDENCE_IN_PROMPT) : allEvidence;
        Map<String, EvidenceView> byId = new LinkedHashMap<>();
        evidence.forEach(view -> byId.putIfAbsent(view.id(), view));

        String system = promptLoader.load("prompts/claim.v1.system.md");
        String user = promptLoader.render(promptLoader.load("prompts/claim.v1.user.md"), Map.of(
                "subQuestions", formatSubQuestions(input),
                "evidence", formatEvidence(evidence),
                "max", String.valueOf(MAX_CLAIMS)));

        Usage usage = Usage.ZERO;
        List<ClaimDraft> drafts;
        try {
            LlmResponse first = call(input.taskId(), system, user);
            usage = usage.plus(first.usage());
            usageRepository.record(input.taskId(), "claim.synthesize",
                    properties.getClaimModel(), first.usage());
            try {
                drafts = parse(first.content(), input.taskId(), byId);
            } catch (ClaimParseException firstError) {
                log.warn("claim JSON invalid for task {}, repairing: {}",
                        input.taskId(), firstError.getMessage());
                String repairUser = user + "\n\n上一次输出无效，原因：" + firstError.getMessage()
                        + "\n请只输出修正后的严格 JSON 对象。";
                LlmResponse repair = call(input.taskId(), system, repairUser);
                usage = usage.plus(repair.usage());
                usageRepository.record(input.taskId(), "claim.repair",
                        properties.getClaimModel(), repair.usage());
                try {
                    drafts = parse(repair.content(), input.taskId(), byId);
                } catch (ClaimParseException repairError) {
                    log.warn("claim JSON still invalid, using evidence fallback claims");
                    drafts = fallbackClaims(input, evidence);
                }
            }
            if (drafts.isEmpty()) {
                drafts = fallbackClaims(input, evidence);
            }
        } catch (RuntimeException e) {
            if (attempt() >= 3) {
                log.warn("LLM unavailable for claims, using evidence fallback: {}", e.getMessage());
                drafts = fallbackClaims(input, evidence);
            } else {
                throw e;
            }
        }

        persist(input.taskId(), drafts);
        return drafts;
    }

    private LlmResponse call(String taskId, String system, String user) {
        return llm.complete(new LlmRequest(taskId, "claim.synthesize", properties.getClaimModel(),
                system, user, properties.getExtractTemperature(), properties.getMaxTokens()));
    }

    private List<ClaimDraft> parse(String raw, String taskId, Map<String, EvidenceView> allowedEvidence) {
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ClaimParseException("not valid JSON: " + e.getMessage());
        }
        JsonNode array = root.path("claims");
        if (!array.isArray()) {
            throw new ClaimParseException("missing claims array");
        }
        List<ClaimDraft> drafts = new ArrayList<>();
        Set<String> seenStatements = new HashSet<>();
        int index = 0;
        for (JsonNode node : array) {
            if (drafts.size() >= MAX_CLAIMS) {
                break;
            }
            String statement = truncate(text(node, "statement"), 2000);
            if (statement.length() < 10 || !seenStatements.add(statement)) {
                continue;
            }
            List<String> evidenceIds = new ArrayList<>();
            JsonNode ids = node.path("evidenceIds");
            if (ids.isArray()) {
                for (JsonNode idNode : ids) {
                    String evidenceId = idNode.asText("").trim();
                    if (allowedEvidence.containsKey(evidenceId) && !evidenceIds.contains(evidenceId)) {
                        evidenceIds.add(evidenceId);
                    }
                    if (evidenceIds.size() >= 5) {
                        break;
                    }
                }
            }
            if (evidenceIds.isEmpty()) {
                continue;
            }
            index++;
            String sectionKey = text(node, "sectionKey");
            if (sectionKey.isBlank()) {
                sectionKey = "s" + index;
            }
            int importance = Math.max(1, Math.min(5, node.path("importance").asInt(3)));
            String claimId = Ids.stableId(taskId, "claim", sectionKey, statement);
            drafts.add(new ClaimDraft(claimId, sectionKey, statement, evidenceIds, importance));
        }
        return drafts;
    }

    private List<ClaimDraft> fallbackClaims(SynthesizeInput input, List<EvidenceView> evidence) {
        boolean chinese = input.subQuestions().stream()
                .anyMatch(q -> containsCjk(q.question()));
        Map<String, List<EvidenceView>> bySubQuestion = evidence.stream()
                .collect(Collectors.groupingBy(EvidenceView::subQuestionId, LinkedHashMap::new, Collectors.toList()));
        List<ClaimDraft> drafts = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, List<EvidenceView>> entry : bySubQuestion.entrySet()) {
            if (drafts.size() >= 10) {
                break;
            }
            EvidenceView first = entry.getValue().get(0);
            String statement = (chinese ? "根据已收集证据：" : "According to the collected evidence: ")
                    + truncate(first.quote(), 260);
            if (statement.length() < 10) {
                continue;
            }
            index++;
            String sectionKey = "s" + index;
            String claimId = Ids.stableId(input.taskId(), "claim", sectionKey, statement);
            drafts.add(new ClaimDraft(claimId, sectionKey, statement, List.of(first.id()), 2));
        }
        return drafts;
    }

    private void persist(String taskId, List<ClaimDraft> drafts) {
        for (ClaimDraft draft : drafts) {
            claimRepository.save(new ClaimRow(draft.id(), taskId, draft.sectionKey(),
                    draft.statement(), draft.importance(), "UNVERIFIED", 0.0));
            for (String evidenceId : draft.evidenceIds()) {
                citationRepository.save(Ids.stableId(draft.id(), "cit", evidenceId),
                        draft.id(), evidenceId, "DIRECT");
            }
        }
    }

    private static String formatSubQuestions(SynthesizeInput input) {
        return input.subQuestions().stream()
                .map(q -> "- subQuestionId=" + q.id() + " question=" + q.question())
                .collect(Collectors.joining("\n"));
    }

    private static String formatEvidence(List<EvidenceView> evidence) {
        return evidence.stream()
                .map(e -> "- evidenceId=" + e.id()
                        + " subQuestionId=" + e.subQuestionId()
                        + " source=" + (e.sourceUrl() == null ? "" : e.sourceUrl())
                        + " quote=\"" + truncate(e.quote(), 400) + "\"")
                .collect(Collectors.joining("\n"));
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ClaimParseException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ClaimParseException("no JSON object found");
        }
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean containsCjk(String text) {
        return text != null && text.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static int attempt() {
        return Activity.getExecutionContext().getInfo().getAttempt();
    }

    private static final class ClaimParseException extends RuntimeException {
        private ClaimParseException(String message) {
            super(message);
        }
    }
}
