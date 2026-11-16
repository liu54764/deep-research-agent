package com.dra.infra.llm;

import com.dra.contracts.dto.Usage;
import com.dra.domain.llm.LlmPort;
import com.dra.domain.llm.LlmRequest;
import com.dra.domain.llm.LlmResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * OpenAI-compatible adapter. Point dra.llm.base-url at DeepSeek now, and at the
 * self-built AI gateway later — no business code changes.
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "true")
public class SpringAiLlmClient implements LlmPort {

    private final OpenAiChatModel chatModel;
    private final LlmProperties properties;

    public SpringAiLlmClient(LlmProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "dra.llm.enabled=true requires DRA_LLM_API_KEY (or dra.llm.api-key)");
        }
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getPlanModel())
                        .temperature(properties.getPlanTemperature())
                        .maxTokens(properties.getMaxTokens())
                        .build())
                .build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(request.model())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(request.systemPrompt()),
                new UserMessage(request.userPrompt())), options);

        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();

        long promptTokens = 0;
        long completionTokens = 0;
        org.springframework.ai.chat.metadata.Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        }
        long costMicros = properties.costMicros(promptTokens, completionTokens);
        return new LlmResponse(content, new Usage(promptTokens, completionTokens, costMicros),
                request.model(), null);
    }
}
