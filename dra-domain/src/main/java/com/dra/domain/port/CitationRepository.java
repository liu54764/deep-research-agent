package com.dra.domain.port;

import java.util.List;

public interface CitationRepository {

    void save(String citationId, String claimId, String evidenceId, String supportType);

    List<String> findEvidenceIds(String claimId);
}
