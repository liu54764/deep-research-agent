package com.dra.eval.report;

import com.dra.eval.judge.JudgeResult;
import com.dra.eval.runner.QuestionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalMetricsTest {

    /**
     * A question whose J2 verdict never parsed contributes 0/0, which used to disappear
     * from the ratio: two questions, one unmeasured, still read as a clean 1.000.
     */
    @Test
    void gcrCountsOnlyQuestionsWhoseVerdictParsed() {
        EvalMetrics metrics = EvalMetrics.of(List.of(
                result("q-judged", judge(3.6, 3.6)),
                result("q-j2-failed", JudgeResult.empty())), 1);

        assertEquals(2, metrics.succeeded());
        assertEquals(1, metrics.gcrQuestions());
        assertEquals(1.0, metrics.gcr(), 1e-9);
        assertEquals(3.6, metrics.totalWeight(), 1e-9);
    }

    @Test
    void gcrIsAbsentWhenNoVerdictParsed() {
        EvalMetrics metrics = EvalMetrics.of(List.of(
                result("q1", JudgeResult.empty()),
                result("q2", JudgeResult.empty())), 0);

        assertEquals(0, metrics.gcrQuestions());
        assertEquals(0.0, metrics.gcr(), 1e-9);
    }

    private static QuestionResult result(String id, JudgeResult judge) {
        return new QuestionResult(id, "regression", "task-" + id, "SUCCEEDED", null, "DEEP",
                1_000, 10_000, 1_000, 5, 5,
                com.dra.eval.score.rule.RuleScorer.RuleResult.empty(), judge, List.of(), true, false);
    }

    private static JudgeResult judge(double covered, double total) {
        return new JudgeResult(10, 8, 2, 0, 0, 5, 5, 0, 4, covered, total,
                0, 0, 0, 10, 1, 0, 100, 50, 1_000, 0, 0,
                List.of(), List.of(), List.of());
    }
}
