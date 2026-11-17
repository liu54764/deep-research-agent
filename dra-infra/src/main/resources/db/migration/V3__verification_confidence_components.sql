-- Confidence components persisted for calibration. Rows written before this migration keep NULL,
-- so the evaluator can tell "no components" from a genuine zero.
ALTER TABLE verification
    ADD COLUMN judge_confidence DECIMAL(4,3) NULL AFTER checked_source_ids,
    ADD COLUMN rule_ratio       DECIMAL(4,3) NULL AFTER judge_confidence,
    ADD COLUMN diversity_score  DECIMAL(4,3) NULL AFTER rule_ratio,
    ADD COLUMN confidence       DECIMAL(4,3) NULL AFTER diversity_score;
