package com.aibox.platform.execution;

import java.util.UUID;

public final class RunCancelledException extends RuntimeException {

    public RunCancelledException(UUID runId) {
        super("Run was cancelled: " + runId);
    }
}
