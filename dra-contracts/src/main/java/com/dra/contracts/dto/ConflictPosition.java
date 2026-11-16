package com.dra.contracts.dto;

import java.util.List;

/** One side of a disagreement: a real claim ID, its stance, and the URLs supporting it. */
public record ConflictPosition(
        String claimId,
        String stance,
        List<String> urls) {

    public ConflictPosition {
        urls = urls == null ? List.of() : List.copyOf(urls);
    }
}
