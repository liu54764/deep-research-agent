package com.dra.common.error;

/** Stable business error codes shared by API, activities and reports. */
public enum ErrorCode {
    VALIDATION_ERROR,
    UNAUTHORIZED,
    TASK_NOT_FOUND,
    IDEMPOTENCY_CONFLICT,
    TASK_TERMINAL,
    REPORT_NOT_READY,
    RATE_LIMITED,
    TEMPORAL_UNAVAILABLE,
    PROGRESS_UNAVAILABLE,
    SEARCH_UNAVAILABLE,
    FETCH_UNAVAILABLE,
    LLM_UNAVAILABLE,
    BUDGET_EXHAUSTED,
    INTERNAL_ERROR
}
