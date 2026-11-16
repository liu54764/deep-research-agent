package com.dra.worker.activity.stub;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ConflictInput;
import com.dra.contracts.dto.ConflictReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stub mode never invents disagreements: an empty conflict report is the honest answer. */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubConflictActivities implements ResearchActivities.Conflict {

    @Override
    public ConflictReport detect(ConflictInput input) {
        return ConflictReport.empty();
    }
}
