package com.dra.domain.port;

import com.dra.contracts.dto.Usage;

public interface UsageRepository {

    void record(String taskId, String activity, String profile, Usage usage);
}
