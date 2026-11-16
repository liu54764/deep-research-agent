package com.dra.contracts.dto;

import java.util.List;

public record ExtractResult(List<String> evidenceIds, Usage usage) {

    public ExtractResult {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        usage = usage == null ? Usage.ZERO : usage;
    }
}
