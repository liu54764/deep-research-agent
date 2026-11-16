package com.dra.contracts.dto;

public record SearchInput(String taskId, int round, SubQuestion subQuestion, int topK) {

    public SearchInput {
        topK = topK <= 0 ? 8 : topK;
    }
}
