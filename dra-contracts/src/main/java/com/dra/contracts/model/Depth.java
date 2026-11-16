package com.dra.contracts.model;

/** Research depth presets. Budgets are upper bounds, not targets. */
public enum Depth {
    QUICK(3, 1, 5, 15, 150_000, 10),
    STANDARD(5, 2, 8, 40, 500_000, 15),
    DEEP(8, 3, 10, 80, 1_500_000, 30);

    private final int subQuestions;
    private final int maxRounds;
    private final int topK;
    private final int maxSources;
    private final long defaultBudgetTokens;
    private final int defaultDeadlineMinutes;

    Depth(int subQuestions, int maxRounds, int topK, int maxSources,
          long defaultBudgetTokens, int defaultDeadlineMinutes) {
        this.subQuestions = subQuestions;
        this.maxRounds = maxRounds;
        this.topK = topK;
        this.maxSources = maxSources;
        this.defaultBudgetTokens = defaultBudgetTokens;
        this.defaultDeadlineMinutes = defaultDeadlineMinutes;
    }

    public int subQuestions() {
        return subQuestions;
    }

    public int maxRounds() {
        return maxRounds;
    }

    public int topK() {
        return topK;
    }

    public int maxSources() {
        return maxSources;
    }

    public long defaultBudgetTokens() {
        return defaultBudgetTokens;
    }

    public int defaultDeadlineMinutes() {
        return defaultDeadlineMinutes;
    }
}
