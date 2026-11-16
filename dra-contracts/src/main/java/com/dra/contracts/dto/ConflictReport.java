package com.dra.contracts.dto;

import java.util.List;

public record ConflictReport(
        List<ConflictDraft> conflicts,
        Usage usage) {

    public ConflictReport {
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        usage = usage == null ? Usage.ZERO : usage;
    }

    public static ConflictReport empty() {
        return new ConflictReport(List.of(), Usage.ZERO);
    }
}
