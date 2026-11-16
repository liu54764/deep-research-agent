package com.dra.domain.llm;

/** Provider-agnostic LLM call; adapters speak OpenAI-compatible HTTP. */
public record LlmRequest(
        String taskId,
        String activity,
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens) {

    public LlmRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt is required");
        }
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
    }
}
