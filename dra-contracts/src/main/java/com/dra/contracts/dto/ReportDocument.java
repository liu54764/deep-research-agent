package com.dra.contracts.dto;

import java.util.List;
import java.util.Map;

/**
 * Structured report stored in {@code report.meta_json} and returned by
 * {@code GET /tasks/{id}/report?format=json}. Everything here is produced by
 * deterministic server-side assembly — the model only writes section prose.
 */
public record ReportDocument(
        List<Section> sections,
        List<Conflict> conflicts,
        List<Reference> references,
        Stats stats,
        Map<String, String> meta) {

    public ReportDocument {
        sections = sections == null ? List.of() : List.copyOf(sections);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        references = references == null ? List.of() : List.copyOf(references);
        stats = stats == null ? new Stats("UNKNOWN", 0, 0, 0, 0, 0, 0, 0, List.of()) : stats;
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public record Section(String key, String title, List<Claim> claims) {
        public Section {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** A claim that survived verification and is rendered in the report body. */
    public record Claim(
            String id,
            String statement,
            String status,
            double confidence,
            List<Citation> citations) {

        public Claim {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    public record Citation(String ref, String url, String title, String quote) {
    }

    public record Conflict(String topic, String summary, List<Position> positions) {
        public Conflict {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    public record Position(String claimId, String statement, String stance, List<String> urls) {
        public Position {
            urls = urls == null ? List.of() : List.copyOf(urls);
        }
    }

    public record Reference(int n, String url, String title, String accessedAt) {
    }

    public record Stats(
            String stopReason,
            long durationMs,
            long tokens,
            long costMicros,
            int sourcesFetched,
            int evidence,
            int claims,
            double verifiedRatio,
            List<String> warnings) {

        public Stats {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
