package com.dra.worker.activity;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.PlanResult;
import com.dra.contracts.dto.RefineInput;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.TaskContext;
import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import com.dra.domain.port.PlanRepository;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real planner: LLM JSON with validation, one repair attempt, then a deterministic fallback. */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class PlanActivitiesImpl implements ResearchActivities.Plan {

    private static final Logger log = LoggerFactory.getLogger(PlanActivitiesImpl.class);
    private static final String PROMPT_VERSION = "plan.v2";
    private static final int PLAN_VERSION = 1;

    private final LlmPort llm;
    private final PlanRepository planRepository;
    private final UsageRepository usageRepository;
    private final PromptLoader promptLoader;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public PlanActivitiesImpl(LlmPort llm, PlanRepository planRepository, UsageRepository usageRepository,
                              PromptLoader promptLoader, LlmProperties properties, ObjectMapper objectMapper) {
        this.llm = llm;
        this.planRepository = planRepository;
        this.usageRepository = usageRepository;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlanResult create(TaskContext context) {
        String system = promptLoader.load("prompts/plan.v2.system.md");
        String user = promptLoader.render(promptLoader.load("prompts/plan.v2.user.md"),
                Map.of(
                        "question", context.question(),
                        "depth", context.depth().name(),
                        "count", String.valueOf(context.depth().subQuestions()),
                        "language", languageHint(context.language()),
                        "today", LocalDate.now().toString()));

        Usage usage = Usage.ZERO;
        List<SubQuestion> subQuestions;
        String warning = "";
        try {
            LlmResponse first = llm.complete(new LlmRequest(context.taskId(), "plan.create",
                    properties.getPlanModel(), system, user,
                    properties.getPlanTemperature(), properties.getMaxTokens()));
            usage = usage.plus(first.usage());
            usageRepository.record(context.taskId(), "plan.create", properties.getPlanModel(), first.usage());
            try {
                subQuestions = parse(first.content(), context);
            } catch (PlanParseException firstError) {
                log.warn("plan JSON invalid for task {}, attempting repair: {}",
                        context.taskId(), firstError.getMessage());
                String repairUser = user + "\n\n上一次输出无效，原因：" + firstError.getMessage()
                        + "\n请只输出修正后的严格 JSON 对象。";
                LlmResponse repair = llm.complete(new LlmRequest(context.taskId(), "plan.repair",
                        properties.getPlanModel(), system, repairUser,
                        properties.getPlanTemperature(), properties.getMaxTokens()));
                usage = usage.plus(repair.usage());
                usageRepository.record(context.taskId(), "plan.repair", properties.getPlanModel(), repair.usage());
                try {
                    subQuestions = parse(repair.content(), context);
                } catch (PlanParseException repairError) {
                    subQuestions = fallbackPlan(context);
                    warning = "LLM plan invalid after repair, deterministic fallback used";
                }
            }
        } catch (RuntimeException e) {
            if (Activity.getExecutionContext().getInfo().getAttempt() >= 3) {
                log.warn("LLM unavailable for plan after retries, using fallback: {}", e.getMessage());
                subQuestions = fallbackPlan(context);
                warning = "LLM unavailable, deterministic fallback used";
            } else {
                throw e;
            }
        }

        String planId = Ids.stableId(context.taskId(), "plan", String.valueOf(PLAN_VERSION));
        planRepository.savePlan(planId, context.taskId(), PLAN_VERSION, subQuestions,
                properties.getPlanModel(), PROMPT_VERSION);
        return new PlanResult(subQuestions, usage, PROMPT_VERSION, !warning.isEmpty(), warning);
    }

    @Override
    public PlanResult refine(RefineInput input) {
        // M6.2: refine needs coverage-driven query generation; the stub coverage stops at
        // round one for now, so return the current plan unchanged.
        return new PlanResult(input.subQuestions(), Usage.ZERO, PROMPT_VERSION);
    }

    private List<SubQuestion> parse(String raw, TaskContext context) {
        String json = extractJson(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new PlanParseException("not valid JSON: " + e.getMessage());
        }
        JsonNode array = root.path("subQuestions");
        if (!array.isArray() || array.isEmpty()) {
            array = root.path("outline");
        }
        if (!array.isArray() || array.isEmpty()) {
            throw new PlanParseException("missing non-empty subQuestions array");
        }

        int max = Math.min(8, Math.max(3, context.depth().subQuestions() + 1));
        List<SubQuestion> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int seq = 0;
        for (JsonNode node : array) {
            if (seq >= max) {
                break;
            }
            String title = truncate(text(node, "title"), 200);
            String question = truncate(text(node, "question"), 1000);
            if (question.isBlank() && title.isBlank()) {
                continue;
            }
            if (question.isBlank()) {
                question = title;
            }
            if (title.isBlank()) {
                title = truncate(question, 80);
            }
            if (!seen.add(question)) {
                continue;
            }
            List<String> queries = queries(node.path("queries"), question);
            seq++;
            String id = Ids.stableId(context.taskId(), "plan", String.valueOf(PLAN_VERSION),
                    String.valueOf(seq), question);
            result.add(new SubQuestion(id, seq, title, question, queries, "OPEN"));
        }
        if (result.isEmpty()) {
            throw new PlanParseException("no usable sub-questions");
        }
        return result;
    }

    private static List<String> queries(JsonNode node, String question) {
        List<String> queries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode queryNode : node) {
                String query = truncate(queryNode.asText("").trim(), 200);
                if (!query.isBlank() && seen.add(query)) {
                    queries.add(query);
                }
                if (queries.size() >= 3) {
                    break;
                }
            }
        }
        if (queries.isEmpty()) {
            queries.add(truncate(question, 200));
        }
        return queries;
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlanParseException("empty response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new PlanParseException("no JSON object found");
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

    private List<SubQuestion> fallbackPlan(TaskContext context) {
        boolean chinese = containsCjk(context.question());
        String[] titles = chinese
                ? new String[]{"背景与定义", "现状与主流做法", "对比、风险与结论"}
                : new String[]{"Background and definitions", "Current approaches", "Trade-offs and risks"};
        String[] suffixes = chinese
                ? new String[]{" 背景 定义", " 现状 主流做法", " 对比 风险"}
                : new String[]{" background overview", " current approaches", " trade-offs risks"};

        List<SubQuestion> result = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int seq = i + 1;
            String question = context.question() + suffixes[i];
            String id = Ids.stableId(context.taskId(), "plan-fallback", String.valueOf(seq), question);
            result.add(new SubQuestion(id, seq, titles[i], question,
                    List.of(question, (chinese ? "调研 " : "research ") + question), "OPEN"));
        }
        return result;
    }

    private static boolean containsCjk(String text) {
        return text != null && text.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private static String languageHint(String language) {
        if ("zh".equalsIgnoreCase(language)) {
            return "中文";
        }
        if ("en".equalsIgnoreCase(language)) {
            return "English";
        }
        return "auto（与问题语言一致）";
    }

    private static final class PlanParseException extends RuntimeException {
        private PlanParseException(String message) {
            super(message);
        }
    }
}
