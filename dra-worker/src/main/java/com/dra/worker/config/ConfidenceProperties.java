package com.dra.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Confidence formula knobs (M1 §8.7 / M9-eval §7). The defaults keep the M6 behavior
 * until calibration data says otherwise; conflict penalty is applied after CONFLICT.
 */
@ConfigurationProperties(prefix = "dra.verify.confidence")
public class ConfidenceProperties {

    private double judgeWeight = 0.5;
    private double ruleWeight = 0.3;
    private double diversityWeight = 0.2;
    private double conflictPenalty = 0.2;
    private int maxPenalizedConflicts = 1;

    public double getJudgeWeight() {
        return judgeWeight;
    }

    public void setJudgeWeight(double judgeWeight) {
        this.judgeWeight = judgeWeight;
    }

    public double getRuleWeight() {
        return ruleWeight;
    }

    public void setRuleWeight(double ruleWeight) {
        this.ruleWeight = ruleWeight;
    }

    public double getDiversityWeight() {
        return diversityWeight;
    }

    public void setDiversityWeight(double diversityWeight) {
        this.diversityWeight = diversityWeight;
    }

    public double getConflictPenalty() {
        return conflictPenalty;
    }

    public void setConflictPenalty(double conflictPenalty) {
        this.conflictPenalty = conflictPenalty;
    }

    public int getMaxPenalizedConflicts() {
        return maxPenalizedConflicts;
    }

    public void setMaxPenalizedConflicts(int maxPenalizedConflicts) {
        this.maxPenalizedConflicts = maxPenalizedConflicts;
    }
}
