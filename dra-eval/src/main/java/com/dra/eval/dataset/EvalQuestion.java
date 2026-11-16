package com.dra.eval.dataset;

import java.util.List;

/**
 * One eval question, mirroring {@code eval/dataset/schema.json}. Optional fields use
 * wrappers so a missing flag is distinguishable from an explicit {@code false}.
 */
public record EvalQuestion(
        String id,
        String datasetVersion,
        String split,
        String category,
        String question,
        String language,
        String depth,
        Gold gold,
        Boolean contested,
        String notes) {

    public EvalQuestion {
        gold = gold == null
                ? new Gold("keypoints", false, null, null, List.of(), List.of())
                : gold;
        notes = notes == null ? "" : notes;
    }

    public boolean exact() {
        return "exact".equals(gold.type());
    }

    public boolean verified() {
        return Boolean.TRUE.equals(gold.verified());
    }

    public boolean isContested() {
        return Boolean.TRUE.equals(contested);
    }

    public record Gold(
            String type,
            Boolean verified,
            String checkedAt,
            String goldAnswer,
            List<KeyPoint> keyPoints,
            List<ConflictPair> conflicts) {

        public Gold {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }

    public record KeyPoint(
            String id,
            double weight,
            String statement,
            List<String> aliases,
            List<String> referenceUrls) {

        public KeyPoint {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            referenceUrls = referenceUrls == null ? List.of() : List.copyOf(referenceUrls);
        }
    }

    public record ConflictPair(String id, String sideA, String sideB) {
    }
}
