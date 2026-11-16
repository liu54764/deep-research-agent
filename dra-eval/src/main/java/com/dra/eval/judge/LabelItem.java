package com.dra.eval.judge;

/**
 * One human-calibration sample. {@code humanVerdict} is filled by hand in
 * {@code eval/labels/<runId>.jsonl}; the judge verdict is pre-filled so the labeler stays
 * blind to nothing except the reference answer.
 */
public record LabelItem(
        String kind,
        String questionId,
        String subjectId,
        String prompt,
        String judgeVerdict,
        String humanVerdict) {

    public LabelItem withHuman(String human) {
        return new LabelItem(kind, questionId, subjectId, prompt, judgeVerdict, human);
    }
}
