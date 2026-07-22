package com.aibox.api;

import com.aibox.platform.execution.RunEventPublisher;
import com.aibox.platform.execution.RunOutputService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Primary
@Component
public class SseRunEventPublisher implements RunEventPublisher {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1_000L;

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(
            UUID runId,
            String currentStatus,
            List<RunOutputService.RunOutputEventView> replayEvents
    ) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("runId", runId, "status", currentStatus)));
            for (RunOutputService.RunOutputEventView replayEvent : replayEvents) {
                Map<String, Object> data = new java.util.LinkedHashMap<>(replayEvent.data());
                data.put("eventId", replayEvent.id());
                emitter.send(SseEmitter.event()
                        .id(Long.toString(replayEvent.id()))
                        .name("output")
                        .data(Map.copyOf(data)));
            }
        } catch (IOException exception) {
            remove(runId, emitter);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @Override
    public void publish(UUID runId, String eventType, Map<String, Object> data) {
        List<SseEmitter> subscribers = emitters.getOrDefault(runId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : subscribers) {
            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventType).data(data);
                Object eventId = data.get("eventId");
                if (eventId != null) event.id(eventId.toString());
                emitter.send(event);
                if ("completed".equals(eventType) || "failed".equals(eventType)) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException exception) {
                remove(runId, emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    private void remove(UUID runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> runEmitters = emitters.get(runId);
        if (runEmitters == null) {
            return;
        }
        runEmitters.remove(emitter);
        if (runEmitters.isEmpty()) {
            emitters.remove(runId, runEmitters);
        }
    }
}

