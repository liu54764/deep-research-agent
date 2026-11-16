package com.dra.domain.model;

import com.dra.contracts.dto.TaskEvent;

public record ProgressEvent(String streamId, TaskEvent event) {
}
