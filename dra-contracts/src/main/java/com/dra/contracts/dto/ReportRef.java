package com.dra.contracts.dto;

public record ReportRef(String reportId, int version, String markdown, Usage usage) {

    public ReportRef {
        markdown = markdown == null ? "" : markdown;
        usage = usage == null ? Usage.ZERO : usage;
    }
}
