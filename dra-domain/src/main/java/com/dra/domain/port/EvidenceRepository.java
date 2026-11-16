package com.dra.domain.port;

import com.dra.domain.model.EvidenceRow;
import com.dra.domain.model.EvidenceView;

import java.util.List;

public interface EvidenceRepository {

    void save(EvidenceRow evidence);

    List<EvidenceView> findByIds(String taskId, List<String> evidenceIds);
}
