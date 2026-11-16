package com.dra.contracts.dto;

import java.util.List;

/**
 * A detected disagreement between claims. The agent never adjudicates who is right:
 * the report renders both sides with their sources.
 */
public record ConflictDraft(
        String topic,
        String summary,
        List<ConflictPosition> positions) {

    public ConflictDraft {
        topic = topic == null ? "" : topic.trim();
        summary = summary == null ? "" : summary.trim();
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
