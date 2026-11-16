package com.dra.worker.activity.stub;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.CoverageInput;
import com.dra.contracts.dto.CoverageReport;
import com.dra.contracts.dto.SubQuestionCoverage;
import com.dra.contracts.dto.Usage;
import com.dra.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StubCoverageActivities implements ResearchActivities.Coverage {

    private final WorkerProperties properties;

    public StubCoverageActivities(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public CoverageReport assess(CoverageInput input) {
        StubSupport.sleep(properties.getCoverageSleepMs());
        List<SubQuestionCoverage> coverage = input.subQuestions().stream()
                .map(subQuestion -> new SubQuestionCoverage(subQuestion.id(), "COVERED", 0.9, List.of()))
                .toList();
        return new CoverageReport(properties.getCoverageRatio(), coverage, Usage.tokens(400, 100));
    }
}
