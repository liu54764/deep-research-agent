package com.dra.domain.llm;

public interface LlmPort {

    LlmResponse complete(LlmRequest request);
}
