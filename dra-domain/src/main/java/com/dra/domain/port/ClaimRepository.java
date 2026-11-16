package com.dra.domain.port;

import com.dra.domain.model.ClaimRow;

import java.util.Optional;

public interface ClaimRepository {

    void save(ClaimRow claim);

    Optional<ClaimRow> findById(String claimId);

    /**
     * Rewrites only the computed confidence. Used after conflict detection so the report
     * reflects the conflict penalty without re-running verification.
     */
    void updateConfidence(String claimId, double confidence);
}
