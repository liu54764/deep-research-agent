package com.dra.worker.activity;

import com.dra.common.hash.Hashing;
import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ExtractInput;
import com.dra.contracts.dto.ExtractResult;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.SourceContent;
import com.dra.domain.port.EvidenceRepository;
import com.dra.domain.port.SourceRepository;
import com.dra.domain.port.UsageRepository;
import com.dra.domain.util.QuoteValidator;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts verbatim evidence from a fetched source. Every model-produced quote is
 * re-checked against the stored body; a quote that is not a literal substring is dropped.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class ExtractActivitiesImpl implements ResearchActivities.Extract {

    private static final Logger log = LoggerFactory.getLogger(ExtractActivitiesImpl.class);
    private static final String PROMPT_VERSION = "extract.v1";
    private static final int MAX_CONTENT_CHARS = 12_000;
    private static final int MAX_FACTS = 20;
    private static final int MAX_FACTS_PER_SUB_QUESTION = 6;

    private final LlmPort llm;
    private final SourceRepository sources;
    private final EvidenceRepository evidenceRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public ExtractActivitiesImpl(LlmPort llm, SourceRepository sources, EvidenceRepository evidenceRepository,
                                 UsageRepository usageRepository, PromptLoader promptLoader,
                                 LlmProperties properties, ObjectMapper objectMapper) {
        this.llm = llm;
        this.sources = sources;
        this.evidenceRepository = evidenceRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractResult extract(ExtractInput input) {
        Optional<SourceContent> maybe = sources.findContent(input.sourceId());
        if (maybe.isEmpty() || maybe.get().content() == null || maybe.get().content().isBlank()) {
            return new ExtractResult(List.of(), Usage.ZERO);
        }
        SourceContent source = maybe.get();
        String content = truncate(source.content(), MAX_CONTENT_CHARS);
        String system = promptLoader.load("prompts/extract.v1.system.md");
        String user = promptLoader.render(promptLoader.load("prompts/extract.v1.user.md"), Map.of(
                "title", source.title() == null ? "" : source.title(),
                "url", source.url() == null ? "" : source.url(),
                "subQuestions", formatSubQuestions(input.subQuestions()),
                "content", content));

        Usage usage = Usage.ZERO;
        List<Fact> facts;
        try {
            LlmResponse first = call(input.taskId(), system, user);
            usage = usage.plus(first.usage());
            usageRepository.record(input.taskId(), "extract.evidence",
                    properties.getExtractModel(), first.usage());
            try {
                facts = parse(first.content(), input, content);
            } catch (FactParseException firstError) {
                log.warn("extract JSON invalid for source {}, repairing: {}",
                        input.sourceId(), firstError.getMessage());
                String repairUser = user + "\n\n上一次输出无效，原因：" + firstError.getMessage()
                        + "\n请只输出修正后的严格 JSON 对象。";
                LlmResponse repair = call(input.taskId(), system, repairUser);
                usage = usage.plus(repair.usage());
                usageRepository.record(input.taskId(), "extract.repair",
                        properties.getExtractModel(), repair.usage());
                try {
                    facts = parse(repair.content(), input, content);
                } catch (FactParseException repairError) {
                    log.warn("extract JSON still invalid for source {}, returning no evidence",
                            input.sourceId());
                    facts = List.of();
                }
            }
        } catch (RuntimeException e) {
            if (attempt() >= 3) {
                log.warn("LLM unavailable for extract, returning no evidence: {}", e.getMessage());
                return new ExtractResult(List.of(), usage);
            }
            throw e;
        }
        return new ExtractResult(persist(input, facts), usage);
    }

    private LlmResponse call(String taskId, String system, String user) {
        return llm.complete(new LlmRequest(taskId, "extract.evidence", properties.getExtractModel(),
                system, user, properties.getExtractTemperature(), properties.getMaxTokens()));
    }

    private List<Fact> parse(String raw, ExtractInput input, String content) {
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new FactParseException("not valid JSON: " + e.getMessage());
        }
        JsonNode array = root.path("facts");
        if (!array.isArray()) {
            throw new FactParseException("missing facts array");
        }
        Set<String> subQuestionIds = input.subQuestions().stream()
                .map(SubQuestion::id).collect(Collectors.toSet());
        Map<String, Integer> perSubQuestion = new HashMap<>();
        Set<String> seen = new HashSet<>();
        List<Fact> facts = new ArrayList<>();
        List<String> rejectedSamples = new ArrayList<>();
        int rejectedQuotes = 0;
        int repairedQuotes = 0;
        for (JsonNode node : array) {
            if (facts.size() >= MAX_FACTS) {
                break;
            }
            String subQuestionId = text(node, "subQuestionId");
            String quote = text(node, "quote");
            if (!subQuestionIds.contains(subQuestionId) || quote.length() < 10 || quote.length() > 1000) {
                continue;
            }
            Optional<QuoteValidator.Resolved> resolved = QuoteValidator.resolve(quote, content);
            if (resolved.isEmpty()) {
                rejectedQuotes++;
                if (rejectedSamples.size() < 2) {
                    rejectedSamples.add(abbreviate(quote));
                }
                continue;
            }
            QuoteValidator.Resolved span = resolved.get();
            if (span.rewritten()) {
                repairedQuotes++;
            }
            String key = subQuestionId + "|" + span.quote();
            if (!seen.add(key)) {
                continue;
            }
            int count = perSubQuestion.merge(subQuestionId, 1, Integer::sum);
            if (count > MAX_FACTS_PER_SUB_QUESTION) {
                continue;
            }
            facts.add(new Fact(subQuestionId, span.quote(), truncate(text(node, "aspect"), 200),
                    span.start(), span.end()));
        }
        if (rejectedQuotes > 0) {
            log.info("extract rejected {} non-verbatim quotes for source {}, samples: {}",
                    rejectedQuotes, input.sourceId(), rejectedSamples);
        }
        if (repairedQuotes > 0) {
            log.info("extract repaired {} quotes to the exact source span for source {}",
                    repairedQuotes, input.sourceId());
        }
        return facts;
    }

    private List<String> persist(ExtractInput input, List<Fact> facts) {
        List<String> evidenceIds = new ArrayList<>(facts.size());
        for (Fact fact : facts) {
            String evidenceId = Ids.stableId(input.sourceId(), fact.subQuestionId(), fact.quote());
            String anchorJson = "{\"start\":" + fact.start() + ",\"end\":" + fact.end() + "}";
            evidenceRepository.save(new EvidenceRow(
                    evidenceId,
                    input.taskId(),
                    input.sourceId(),
                    fact.subQuestionId(),
                    input.round(),
                    fact.quote(),
                    Hashing.sha256Hex(fact.quote()),
                    "FULL_TEXT",
                    PROMPT_VERSION,
                    fact.aspect(),
                    anchorJson));
            evidenceIds.add(evidenceId);
        }
        return evidenceIds;
    }

    private static String formatSubQuestions(List<SubQuestion> subQuestions) {
        return subQuestions.stream()
                .map(q -> "- subQuestionId=" + q.id() + " question=" + q.question())
                .collect(Collectors.joining("\n"));
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FactParseException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new FactParseException("no JSON object found");
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

    private static String abbreviate(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static int attempt() {
        return Activity.getExecutionContext().getInfo().getAttempt();
    }

    private record Fact(String subQuestionId, String quote, String aspect, int start, int end) {
    }

    private static final class FactParseException extends RuntimeException {
        private FactParseException(String message) {
            super(message);
        }
    }
}
