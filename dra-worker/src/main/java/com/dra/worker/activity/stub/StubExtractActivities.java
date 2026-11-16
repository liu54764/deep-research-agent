package com.dra.worker.activity.stub;

import com.dra.common.hash.Hashing;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ExtractInput;
import com.dra.contracts.dto.ExtractResult;
import com.dra.contracts.dto.SubQuestion;
import com.dra.contracts.dto.Usage;
import com.dra.domain.model.EvidenceRow;
import com.dra.domain.port.EvidenceRepository;
import com.dra.worker.config.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubExtractActivities implements ResearchActivities.Extract {

    private final EvidenceRepository evidenceRepository;
    private final WorkerProperties properties;

    public StubExtractActivities(EvidenceRepository evidenceRepository, WorkerProperties properties) {
        this.evidenceRepository = evidenceRepository;
        this.properties = properties;
    }

    @Override
    public ExtractResult extract(ExtractInput input) {
        StubSupport.sleep(properties.getExtractSleepMs());
        List<String> evidenceIds = new ArrayList<>(input.subQuestions().size());
        for (SubQuestion subQuestion : input.subQuestions()) {
            String quote = "Mock evidence for " + subQuestion.id() + " from source " + input.sourceId();
            String evidenceId = StubSupport.stableId(
                    input.sourceId() + "|" + subQuestion.id() + "|" + quote);
            evidenceRepository.save(new EvidenceRow(
                    evidenceId,
                    input.taskId(),
                    input.sourceId(),
                    subQuestion.id(),
                    input.round(),
                    quote,
                    Hashing.sha256Hex(quote),
                    "FULL_TEXT",
                    "stub.extract.v1",
                    subQuestion.title(),
                    null));
            evidenceIds.add(evidenceId);
        }
        return new ExtractResult(evidenceIds, Usage.tokens(1_000, 500));
    }
}
