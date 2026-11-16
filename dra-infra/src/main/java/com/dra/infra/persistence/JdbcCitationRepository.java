package com.dra.infra.persistence;

import com.dra.domain.port.CitationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcCitationRepository implements CitationRepository {

    private final JdbcClient jdbc;

    public JdbcCitationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String citationId, String claimId, String evidenceId, String supportType) {
        jdbc.sql("""
                INSERT IGNORE INTO citation (id, claim_id, evidence_id, support_type, created_at)
                VALUES (:id, :claimId, :evidenceId, :supportType, NOW(3))
                """)
                .param("id", citationId)
                .param("claimId", claimId)
                .param("evidenceId", evidenceId)
                .param("supportType", supportType)
                .update();
    }

    @Override
    public List<String> findEvidenceIds(String claimId) {
        return jdbc.sql("""
                SELECT evidence_id
                  FROM citation
                 WHERE claim_id = :claimId
                 ORDER BY created_at
                """)
                .param("claimId", claimId)
                .query(String.class)
                .list();
    }
}
