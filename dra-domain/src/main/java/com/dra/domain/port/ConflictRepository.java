package com.dra.domain.port;

import com.dra.domain.model.ReportConflict;

import java.util.List;

public interface ConflictRepository {

    void save(String conflictId, String taskId, String groupKey, String summary, String positionsJson);

    List<ReportConflict> findByTask(String taskId);
}
