package com.dra.domain.port;

import com.dra.domain.model.ReportRow;

import java.util.Optional;

public interface ReportRepository {

    ReportRow save(String reportId, String taskId, int version, String markdown, String metaJson);

    Optional<ReportRow> findLatest(String taskId);
}
