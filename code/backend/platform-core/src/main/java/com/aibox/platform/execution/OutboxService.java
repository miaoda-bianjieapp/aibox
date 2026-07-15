package com.aibox.platform.execution;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final Clock clock;

    public OutboxService(OutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void append(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        repository.save(new OutboxEventEntity(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                payload,
                clock.instant()
        ));
    }
}
