package com.aibox.platform.task;

public enum RunStatus {
    CREATED,
    VALIDATING,
    QUEUED,
    RUNNING,
    WAITING_CALLBACK,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == PARTIAL
                || this == FAILED
                || this == CANCELLED
                || this == EXPIRED;
    }
}

