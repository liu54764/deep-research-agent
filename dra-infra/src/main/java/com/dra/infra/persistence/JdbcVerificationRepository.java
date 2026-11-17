package com.dra.infra.persistence;

import com.dra.domain.model.VerificationRow;
import com.dra.domain.port.VerificationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcVerificationRepository implements VerificationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcVerificationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(VerificationRow verification) {
        jdbc.sql("""
                INSERT INTO verification
                    (id, claim_id, `round`, rule_verdict, llm_verdict, final_verdict, rationale,
                     checked_source_ids, verifier_profile, prompt_version,
                     judge_confidence, rule_ratio, diversity_score, confidence, created_at)
                VALUES
                    (:id, :claimId, :round, :ruleVerdict, :llmVerdict, :finalVerdict, :rationale,
                     :checkedSourceIds, :verifierProfile, :promptVersion,
                     :judgeConfidence, :ruleRatio, :diversityScore, :confidence, NOW(3))
                ON DUPLICATE KEY UPDATE
                    rule_verdict = VALUES(rule_verdict),
                    llm_verdict = VALUES(llm_verdict),
                    final_verdict = VALUES(final_verdict),
                    rationale = VALUES(rationale),
                    checked_source_ids = VALUES(checked_source_ids),
                    judge_confidence = VALUES(judge_confidence),
                    rule_ratio = VALUES(rule_ratio),
                    diversity_score = VALUES(diversity_score),
                    confidence = VALUES(confidence),
                    created_at = NOW(3)
                """)
                .param("id", verification.id())
                .param("claimId", verification.claimId())
                .param("round", verification.round())
                .param("ruleVerdict", verification.ruleVerdict())
                .param("llmVerdict", verification.llmVerdict())
                .param("finalVerdict", verification.finalVerdict())
                .param("rationale", verification.rationale())
                .param("checkedSourceIds", write(verification.checkedSourceIds()))
                .param("verifierProfile", verification.verifierProfile())
                .param("promptVersion", verification.promptVersion())
                .param("judgeConfidence", verification.judgeConfidence())
                .param("ruleRatio", verification.ruleRatio())
                .param("diversityScore", verification.diversityScore())
                .param("confidence", verification.confidence())
                .update();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize verification json", e);
        }
    }
}
