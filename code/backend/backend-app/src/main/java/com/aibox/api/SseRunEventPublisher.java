package com.aibox.api;

import com.aibox.platform.execution.RunEventPublisher;
import com.aibox.platform.execution.RunOutputService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;
import java.util.function.Supplier;

@Primary
@Component
public class SseRunEventPublisher implements RunEventPublisher {

    private static final long TIMEOUT_MILLIS = 30L * 60L * 1_000L;

    private final Map<UUID, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final LongFunction<SseEmitter> emitterFactory;

    public SseRunEventPublisher() {
        this(SseEmitter::new);
    }

    SseRunEventPublisher(LongFunction<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribe(
            UUID runId,
            long replayAfter,
            Supplier<String> currentStatusSupplier,
            LongFunction<List<RunOutputService.RunOutputEventView>> replayLoader
    ) {
        SseEmitter emitter = emitterFactory.apply(TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(emitter, replayAfter);
        emitter.onCompletion(() -> remove(runId, subscriber));
        emitter.onTimeout(() -> remove(runId, subscriber));
        emitter.onError(error -> remove(runId, subscriber));
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(subscriber);

        boolean terminalSent = false;
        try {
            String currentStatus = currentStatusSupplier.get();
            List<RunOutputService.RunOutputEventView> replayEvents = replayLoader.apply(replayAfter);
            synchronized (subscriber.monitor) {
                sendConnected(subscriber, runId, currentStatus);
                if (replayEvents != null) {
                    for (RunOutputService.RunOutputEventView replayEvent : replayEvents) {
                        sendReplayEvent(subscriber, replayEvent);
                    }
                }
                terminalSent = drainBufferedEvents(subscriber);
                subscriber.replaying = false;
            }
        } catch (IOException | RuntimeException exception) {
            fail(runId, subscriber, exception);
        }
        if (terminalSent) {
            remove(runId, subscriber);
        }
        return emitter;
    }

    @Override
    public void publish(UUID runId, String eventType, Map<String, Object> data) {
        List<Subscriber> runSubscribers = subscribers.get(runId);
        if (runSubscribers == null) return;

        for (Subscriber subscriber : runSubscribers) {
            boolean terminalSent = false;
            try {
                synchronized (subscriber.monitor) {
                    if (subscriber.replaying) {
                        subscriber.bufferedEvents.add(
                                new PendingEvent(eventType, Map.copyOf(data))
                        );
                        continue;
                    }
                    terminalSent = sendLiveEvent(subscriber, eventType, data);
                }
            } catch (IOException | IllegalStateException exception) {
                fail(runId, subscriber, exception);
                continue;
            }
            if (terminalSent) {
                remove(runId, subscriber);
            }
        }
    }

    private void sendConnected(
            Subscriber subscriber,
            UUID runId,
            String currentStatus
    ) throws IOException {
        subscriber.emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of("runId", runId, "status", currentStatus)));
    }

    private void sendReplayEvent(
            Subscriber subscriber,
            RunOutputService.RunOutputEventView replayEvent
    ) throws IOException {
        if (replayEvent.id() <= subscriber.lastOutputEventId) return;
        Map<String, Object> data = new LinkedHashMap<>(replayEvent.data());
        data.put("eventId", replayEvent.id());
        sendEvent(subscriber, "output", Map.copyOf(data), replayEvent.id());
    }

    private boolean drainBufferedEvents(Subscriber subscriber) throws IOException {
        List<PendingEvent> buffered = List.copyOf(subscriber.bufferedEvents);
        subscriber.bufferedEvents.clear();

        List<PendingEvent> outputEvents = buffered.stream()
                .filter(PendingEvent::isOutputWithId)
                .sorted(Comparator.comparingLong(PendingEvent::eventId))
                .toList();
        for (PendingEvent event : outputEvents) {
            sendLiveEvent(subscriber, event.eventType(), event.data());
        }

        for (PendingEvent event : buffered) {
            if (event.isOutputWithId()) continue;
            if (sendLiveEvent(subscriber, event.eventType(), event.data())) {
                return true;
            }
        }
        return false;
    }

    private boolean sendLiveEvent(
            Subscriber subscriber,
            String eventType,
            Map<String, Object> data
    ) throws IOException {
        Long eventId = outputEventId(eventType, data);
        if (eventId != null && eventId <= subscriber.lastOutputEventId) {
            return false;
        }
        sendEvent(subscriber, eventType, data, eventId);
        boolean terminal = "completed".equals(eventType) || "failed".equals(eventType);
        if (terminal) subscriber.emitter.complete();
        return terminal;
    }

    private void sendEvent(
            Subscriber subscriber,
            String eventType,
            Map<String, Object> data,
            Long eventId
    ) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventType).data(data);
        if (eventId != null) event.id(eventId.toString());
        subscriber.emitter.send(event);
        if (eventId != null) subscriber.lastOutputEventId = eventId;
    }

    private static Long outputEventId(String eventType, Map<String, Object> data) {
        if (!"output".equals(eventType)) return null;
        Object value = data.get("eventId");
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void fail(UUID runId, Subscriber subscriber, Throwable exception) {
        remove(runId, subscriber);
        subscriber.emitter.completeWithError(exception);
    }

    private void remove(UUID runId, Subscriber subscriber) {
        CopyOnWriteArrayList<Subscriber> runSubscribers = subscribers.get(runId);
        if (runSubscribers == null) return;
        runSubscribers.remove(subscriber);
        if (runSubscribers.isEmpty()) {
            subscribers.remove(runId, runSubscribers);
        }
    }

    private static final class Subscriber {
        private final SseEmitter emitter;
        private final Object monitor = new Object();
        private final List<PendingEvent> bufferedEvents = new ArrayList<>();
        private long lastOutputEventId;
        private boolean replaying = true;

        private Subscriber(SseEmitter emitter, long lastOutputEventId) {
            this.emitter = emitter;
            this.lastOutputEventId = lastOutputEventId;
        }
    }

    private record PendingEvent(String eventType, Map<String, Object> data) {
        private boolean isOutputWithId() {
            return outputEventId(eventType, data) != null;
        }

        private long eventId() {
            Long value = outputEventId(eventType, data);
            return value == null ? Long.MAX_VALUE : value;
        }
    }
}
