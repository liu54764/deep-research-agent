package com.dra.contracts.model;

public enum TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }
}
