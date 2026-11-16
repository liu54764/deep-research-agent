package com.dra.domain.port;

import com.dra.domain.model.ReportBundle;

import java.util.List;

/** Read model for report assembly: one query per table, joined in the adapter. */
public interface ReportDataRepository {

    ReportBundle load(String taskId, String stopReason, List<String> warnings);
}
