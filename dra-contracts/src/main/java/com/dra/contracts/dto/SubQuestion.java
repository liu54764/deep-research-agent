package com.dra.contracts.dto;

import java.util.List;

public record SubQuestion(
        String id,
        int seq,
        String title,
        String question,
        List<String> queries,
        String status) {

    public SubQuestion {
        queries = queries == null ? List.of() : List.copyOf(queries);
        status = (status == null || status.isBlank()) ? "OPEN" : status;
    }

    public SubQuestion withStatus(String newStatus) {
        return new SubQuestion(id, seq, title, question, queries, newStatus);
    }
}
