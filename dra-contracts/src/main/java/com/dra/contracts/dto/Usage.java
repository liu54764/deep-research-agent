package com.dra.contracts.dto;

/** Token and cost usage of one activity invocation. costMicros is priced by the AI gateway. */
public record Usage(long promptTokens, long completionTokens, long costMicros) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    public Usage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        costMicros = Math.max(0, costMicros);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                costMicros + other.costMicros);
    }

    public static Usage tokens(long promptTokens, long completionTokens) {
        return new Usage(promptTokens, completionTokens, 0);
    }
}
