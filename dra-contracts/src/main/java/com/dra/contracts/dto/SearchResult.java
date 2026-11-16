package com.dra.contracts.dto;

import java.util.List;

public record SearchResult(List<SearchHit> hits, String provider, Usage usage, long latencyMs) {

    public SearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        provider = provider == null ? "unknown" : provider;
        usage = usage == null ? Usage.ZERO : usage;
    }
}
