package com.dra.contracts.model;

/** SSE event type names shared by worker and API. */
public final class EventTypes {

    public static final String PHASE = "phase";
    public static final String SNAPSHOT = "snapshot";
    public static final String PROGRESS = "progress";
    public static final String SOURCE = "source";
    public static final String EVIDENCE = "evidence";
    public static final String CLAIM = "claim";
    public static final String WARNING = "warning";
    public static final String PAUSED = "paused";
    public static final String RESUMED = "resumed";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    private EventTypes() {
    }
}
