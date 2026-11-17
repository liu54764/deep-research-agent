package com.dra.domain.port;

import com.dra.domain.model.SearchQueryRow;

public interface SearchQueryRepository {

    /**
     * Idempotent for at-least-once activities: (task_id, round, query_hash) is unique, so a
     * retried attempt overwrites the previous row instead of duplicating it.
     */
    void record(SearchQueryRow row);
}
