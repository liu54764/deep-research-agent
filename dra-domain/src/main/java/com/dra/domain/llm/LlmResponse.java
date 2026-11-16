package com.dra.domain.llm;

import com.dra.contracts.dto.Usage;

public record LlmResponse(String content, Usage usage, String model, String finishReason) {

    public LlmResponse {
        content = content == null ? "" : content;
        usage = usage == null ? Usage.ZERO : usage;
    }
}
