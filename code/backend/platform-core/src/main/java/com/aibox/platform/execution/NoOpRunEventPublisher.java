package com.aibox.platform.execution;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class NoOpRunEventPublisher implements RunEventPublisher {

    @Override
    public void publish(UUID runId, String eventType, Map<String, Object> data) {
        // Durable state remains available through polling and the outbox table.
    }
}

