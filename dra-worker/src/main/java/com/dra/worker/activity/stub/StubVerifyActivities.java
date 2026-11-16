package com.dra.worker.activity.stub;

import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.Usage;
import com.dra.contracts.dto.Verification;
import com.dra.contracts.dto.VerifyInput;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubVerifyActivities implements ResearchActivities.Verify {

    private final WorkerProperties properties;

    public StubVerifyActivities(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public Verification verify(VerifyInput input) {
        StubSupport.sleep(properties.getVerifySleepMs());
        return new Verification(input.claimId(), "PASS", "SUPPORT", "SUPPORTED",
                0.9, "stub verification", List.of(), Usage.tokens(200, 100));
    }
}
