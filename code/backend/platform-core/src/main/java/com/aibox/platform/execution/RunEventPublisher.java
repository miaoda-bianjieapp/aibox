package com.aibox.platform.execution;

import java.util.Map;
import java.util.UUID;

public interface RunEventPublisher {

    void publish(UUID runId, String eventType, Map<String, Object> data);
}

