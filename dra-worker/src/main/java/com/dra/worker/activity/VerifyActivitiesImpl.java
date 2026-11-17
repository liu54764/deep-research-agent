package com.dra.worker.activity;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.Usage;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.model.EvidenceView;
import com.dra.domain.model.SourceContent;
import com.dra.domain.model.VerificationRow;
import com.dra.domain.port.CitationRepository;
import com.dra.domain.port.ClaimRepository;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.domain.port.VerificationRepository;
import com.dra.domain.util.QuoteValidator;
import com.dra.infra.llm.LlmProperties;
import com.dra.worker.config.ConfidenceProperties;
import com.dra.worker.prompt.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two-channel verification: deterministic quote matching against the stored source,
 * then a cross-model judge. Rule failure always produces UNSUPPORTED, regardless of
 * what the judge says.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class VerifyActivitiesImpl implements ResearchActivities.Verify {

    private static final Logger log = LoggerFactory.getLogger(VerifyActivitiesImpl.class);
    private static final String PROMPT_VERSION = "verify.v1";
    private static final Set<String> JUDGE_VERDICTS =
            Set.of("SUPPORT", "PARTIAL", "REFUTE", "UNCERTAIN");

    private final LlmPort llm;
    private final ClaimRepository claimRepository;
    private final EvidenceRepository evidenceRepository;
    private final CitationRepository citationRepository;
    private final VerificationRepository verificationRepository;
    private final SourceRepository sourceRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ConfidenceProperties confidenceProperties;
    private final ObjectMapper objectMapper;

    public VerifyActivitiesImpl(LlmPort llm, ClaimRepository claimRepository,
                                EvidenceRepository evidenceRepository, CitationRepository citationRepository,
                                VerificationRepository verificationRepository, SourceRepository sourceRepository,
                                UsageRepository usageRepository, PromptLoader promptLoader,
                                LlmProperties properties, ConfidenceProperties confidenceProperties,
                                ObjectMapper objectMapper) {
        this.llm = llm;
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.citationRepository = citationRepository;
        this.verificationRepository = verificationRepository;
        this.sourceRepository = sourceRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.confidenceProperties = confidenceProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Verification verify(VerifyInput input) {
        Optional<ClaimRow> maybeClaim = claimRepository.findById(input.claimId());
        if (maybeClaim.isEmpty()) {
            return new Verification(input.claimId(), "FAIL", "NONE", "UNSUPPORTED",
                    0.0, "claim not found", List.of(), Usage.ZERO);
        }
        ClaimRow claim = maybeClaim.get();
        List<String> evidenceIds = citationRepository.findEvidenceIds(claim.id());
        List<EvidenceView> evidence = evidenceRepository.findByIds(claim.taskId(), evidenceIds);

        Map<String, Optional<SourceContent>> contentCache = new HashMap<>();
        int passed = 0;
        Set<String> checkedSourceIds = new LinkedHashSet<>();
        for (EvidenceView view : evidence) {
            checkedSourceIds.add(view.sourceId());
            if (!"FULL_TEXT".equals(view.origin())) {
                continue;
            }
            Optional<SourceContent> source = contentCache.computeIfAbsent(
                    view.sourceId(), sourceRepository::findContent);
            if (source.isPresent() && source.get().content() != null
                    && QuoteValidator.isSupportedBy(view.quote(), source.get().content())) {
                passed++;
            }
        }
        boolean rulePass = !evidence.isEmpty() && passed > 0;
        double ruleRatio = evidence.isEmpty() ? 0.0 : (double) passed / evidence.size();
        String ruleVerdict = rulePass ? "PASS" : "FAIL";

        Usage usage = Usage.ZERO;
        String llmVerdict = "NONE";
        double llmConfidence = 0.0;
        String rationale = rulePass ? "" : "cited evidence failed rule validation";

        if (rulePass) {
            String system = promptLoader.load("prompts/verify.v1.system.md");
            String user = promptLoader.render(promptLoader.load("prompts/verify.v1.user.md"), Map.of(
                    "statement", claim.statement(),
                    "evidence", formatEvidence(evidence)));
            try {
                LlmResponse response = llm.complete(new LlmRequest(claim.taskId(), "claim.verify",
                        properties.getVerifyModel(), system, user,
                        properties.getVerifyTemperature(), properties.getMaxTokens()));
                usage = response.usage();
                usageRepository.record(claim.taskId(), "claim.verify",
                        properties.getVerifyModel(), usage);
                Judge judge = parseJudge(response.content());
                if (judge == null) {
                    String repairUser = user + "\n\n上一次输出无效，请只输出修正后的严格 JSON 对象。";
                    LlmResponse repair = llm.complete(new LlmRequest(claim.taskId(), "claim.verify.repair",
                            properties.getVerifyModel(), system, repairUser,
                            properties.getVerifyTemperature(), properties.getMaxTokens()));
                    usage = usage.plus(repair.usage());
                    usageRepository.record(claim.taskId(), "claim.verify.repair",
                            properties.getVerifyModel(), repair.usage());
                    judge = parseJudge(repair.content());
                }
                if (judge == null) {
                    llmVerdict = "UNCERTAIN";
                    rationale = "LLM verdict invalid; rule-only verification";
                } else {
                    llmVerdict = judge.verdict();
                    llmConfidence = judge.confidence();
                    rationale = judge.rationale();
                }
            } catch (RuntimeException e) {
                if (attempt() >= 3) {
                    log.warn("LLM unavailable for verify, rule-only: {}", e.getMessage());
                    llmVerdict = "UNCERTAIN";
                    rationale = "LLM unavailable; rule-only verification";
                } else {
                    throw e;
                }
            }
        }

        String finalVerdict;
        if (!rulePass) {
            finalVerdict = "UNSUPPORTED";
        } else if ("REFUTE".equals(llmVerdict)) {
            finalVerdict = "REFUTED";
        } else if ("SUPPORT".equals(llmVerdict)) {
            finalVerdict = "SUPPORTED";
        } else {
            finalVerdict = "PARTIAL";
        }

        double diversity = Math.min(1.0, evidence.size() / 3.0);
        // Confidence before the verdict caps and before the conflict penalty. Persisted with the
        // components so calibration can explain the score instead of only observing it.
        double prePenaltyConfidence = Math.max(0.0, Math.min(1.0,
                confidenceProperties.getJudgeWeight() * llmConfidence
                        + confidenceProperties.getRuleWeight() * ruleRatio
                        + confidenceProperties.getDiversityWeight() * diversity));
        double confidence = prePenaltyConfidence;
        if ("REFUTED".equals(finalVerdict)) {
            confidence = Math.min(confidence, 0.3);
        } else if ("UNSUPPORTED".equals(finalVerdict)) {
            confidence = Math.min(confidence, evidence.isEmpty() ? 0.1 : 0.4);
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        claimRepository.save(new ClaimRow(claim.id(), claim.taskId(), claim.sectionKey(),
                claim.statement(), claim.importance(), finalVerdict, confidence));
        String profile = properties.getVerifyModel();
        verificationRepository.save(new VerificationRow(
                Ids.stableId(claim.id(), "verification", String.valueOf(input.round()), profile),
                claim.id(),
                input.round(),
                ruleVerdict,
                llmVerdict,
                finalVerdict,
                truncate(rationale, 1000),
                List.copyOf(checkedSourceIds),
                profile,
                PROMPT_VERSION,
                llmConfidence,
                ruleRatio,
                diversity,
                prePenaltyConfidence));

        return new Verification(claim.id(), ruleVerdict, llmVerdict, finalVerdict, confidence,
                truncate(rationale, 1000), List.copyOf(checkedSourceIds), usage);
    }

    private Judge parseJudge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
            String verdict = root.path("verdict").asText("").trim().toUpperCase(Locale.ROOT);
            if (!JUDGE_VERDICTS.contains(verdict)) {
                return null;
            }
            double confidence = Math.max(0.0, Math.min(1.0,
                    root.path("confidence").asDouble(0.5)));
            String rationale = truncate(root.path("rationale").asText(""), 1000);
            return new Judge(verdict, confidence, rationale);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatEvidence(List<EvidenceView> evidence) {
        return evidence.stream().limit(10)
                .map(e -> "- source=" + (e.sourceUrl() == null ? "" : e.sourceUrl())
                        + " quote=\"" + truncate(e.quote(), 500) + "\"")
                .collect(Collectors.joining("\n"));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static int attempt() {
        return Activity.getExecutionContext().getInfo().getAttempt();
    }

    private record Judge(String verdict, double confidence, String rationale) {
    }
}
