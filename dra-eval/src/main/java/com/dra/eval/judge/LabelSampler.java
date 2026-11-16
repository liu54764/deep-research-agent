package com.dra.eval.judge;

import com.dra.common.hash.Hashing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic label sampling: the same run always produces the same 16 items, so the
 * template can be filled across sessions without drift.
 */
public final class LabelSampler {

    private LabelSampler() {
    }

    public static List<LabelItem> sample(List<LabelItem> candidates, int j1Limit, int j2Limit) {
        List<LabelItem> sampled = new ArrayList<>(pick(candidates, "J1", j1Limit));
        sampled.addAll(pick(candidates, "J2", j2Limit));
        return sampled;
    }

    private static List<LabelItem> pick(List<LabelItem> candidates, String kind, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return candidates.stream()
                .filter(item -> kind.equals(item.kind()))
                .sorted(Comparator.comparing(item ->
                        Hashing.sha256Hex(item.questionId() + "|" + item.subjectId())))
                .limit(limit)
                .toList();
    }
}
