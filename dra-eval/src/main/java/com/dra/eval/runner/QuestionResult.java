package com.dra.eval.runner;

import com.dra.eval.invariant.InvariantChecker;
import com.dra.eval.judge.JudgeResult;
import com.dra.eval.score.rule.RuleScorer;

import java.util.List;

/** Outcome of one eval question; the unit of {@code per-question.jsonl}. */
public record QuestionResult(
        String questionId,
        String split,
        String taskId,
        String status,
        String error,
        String depth,
        long latencyMs,
        long costMicros,
        long tokens,
        int claims,
        int citations,
        RuleScorer.RuleResult rule,
        JudgeResult judge,
        List<InvariantChecker.Violation> violations,
        boolean goldVerified,
        boolean skipped) {

    public QuestionResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        rule = rule == null ? RuleScorer.RuleResult.empty() : rule;
        judge = judge == null ? JudgeResult.empty() : judge;
    }

    public boolean succeeded() {
        return !skipped && "SUCCEEDED".equals(status);
    }

    public static QuestionResult skipped(String questionId, String split, String depth,
                                         boolean goldVerified, String reason) {
        return new QuestionResult(questionId, split, null, "SKIPPED", reason, depth,
                0, 0, 0, 0, 0, RuleScorer.RuleResult.empty(), JudgeResult.empty(),
                List.of(), goldVerified, true);
    }

    public static QuestionResult failed(String questionId, String split, String taskId, String depth,
                                        long latencyMs, long costMicros, long tokens,
                                        boolean goldVerified, String error) {
        return new QuestionResult(questionId, split, taskId, "ERROR", error, depth,
                latencyMs, costMicros, tokens, 0, 0, RuleScorer.RuleResult.empty(),
                JudgeResult.empty(), List.of(), goldVerified, false);
    }
}
