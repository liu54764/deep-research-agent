package com.dra.worker.activity.stub;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ClaimDraft;
import com.dra.contracts.dto.SynthesizeInput;
import com.dra.domain.model.ClaimRow;
import com.dra.domain.port.ClaimRepository;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubClaimActivities implements ResearchActivities.Claim {

    private final ClaimRepository claimRepository;
    private final WorkerProperties properties;

    public StubClaimActivities(ClaimRepository claimRepository, WorkerProperties properties) {
        this.claimRepository = claimRepository;
        this.properties = properties;
    }

    @Override
    public List<ClaimDraft> synthesize(SynthesizeInput input) {
        StubSupport.sleep(properties.getSynthesizeSleepMs());
        int count = Math.min(2, Math.max(1, input.subQuestions().size()));
        List<ClaimDraft> drafts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String sectionKey = "s" + (i + 1);
            String statement = "Stub claim " + (i + 1) + " based on the collected evidence.";
            String id = StubSupport.stableId(input.taskId() + "|" + sectionKey + "|" + statement);
            claimRepository.save(new ClaimRow(id, input.taskId(), sectionKey, statement, 3, "SUPPORTED", 0.9));
            drafts.add(new ClaimDraft(id, sectionKey, statement, List.of(), 3));
        }
        return drafts;
    }
}
