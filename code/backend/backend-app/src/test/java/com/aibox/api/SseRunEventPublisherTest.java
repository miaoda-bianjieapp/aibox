package com.aibox.api;

import com.aibox.platform.execution.RunOutputService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseRunEventPublisherTest {

    @Test
    void buffersEventsPublishedWhileReplayQueryIsRunning() throws Exception {
        ReplayScenario scenario = new ReplayScenario(false);

        scenario.run();

        assertEquals(List.of(1L, 2L), scenario.emitter().outputEventIds());
        assertEquals("RUNNING", scenario.emitter().connectedStatus());
        assertNull(scenario.emitter().error());
    }

    @Test
    void deduplicatesBufferedEventsAlreadyReturnedByReplayQuery() throws Exception {
        ReplayScenario scenario = new ReplayScenario(true);

        scenario.run();

        assertEquals(List.of(1L, 2L), scenario.emitter().outputEventIds());
        assertNull(scenario.emitter().error());
    }

    private static RunOutputService.RunOutputEventView replayEvent(
            UUID runId,
            long id,
            String delta
    ) {
        return new RunOutputService.RunOutputEventView(
                id,
                runId,
                "main",
                id,
                "append",
                Map.of(
                        "channel", "main",
                        "sequence", id,
                        "type", "append",
                        "delta", delta,
                        "status", "STREAMING"
                ),
                Instant.parse("2026-07-22T00:00:00Z")
        );
    }

    private static Map<String, Object> liveOutput(long id, String delta) {
        return Map.of(
                "eventId", id,
                "channel", "main",
                "sequence", id,
                "type", "append",
                "delta", delta,
                "status", "STREAMING"
        );
    }

    private static final class ReplayScenario {
        private final boolean includeBufferedEventInReplay;
        private final UUID runId = UUID.randomUUID();
        private final CountDownLatch replayStarted = new CountDownLatch(1);
        private final CountDownLatch releaseReplay = new CountDownLatch(1);
        private final AtomicReference<RecordingSseEmitter> emitter = new AtomicReference<>();

        private ReplayScenario(boolean includeBufferedEventInReplay) {
            this.includeBufferedEventInReplay = includeBufferedEventInReplay;
        }

        private void run() throws Exception {
            SseRunEventPublisher publisher = new SseRunEventPublisher(timeout -> {
                RecordingSseEmitter value = new RecordingSseEmitter();
                emitter.set(value);
                return value;
            });
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<SseEmitter> subscription = executor.submit(() -> publisher.subscribe(
                        runId,
                        0,
                        () -> "RUNNING",
                        ignored -> {
                            replayStarted.countDown();
                            await(releaseReplay);
                            List<RunOutputService.RunOutputEventView> events = new ArrayList<>();
                            events.add(replayEvent(runId, 1, "history"));
                            if (includeBufferedEventInReplay) {
                                events.add(replayEvent(runId, 2, "overlap"));
                            }
                            return List.copyOf(events);
                        }
                ));

                assertTrue(replayStarted.await(5, TimeUnit.SECONDS));
                publisher.publish(runId, "output", liveOutput(2, "live"));
                releaseReplay.countDown();
                subscription.get(5, TimeUnit.SECONDS);
            } finally {
                releaseReplay.countDown();
                executor.shutdownNow();
            }
        }

        private RecordingSseEmitter emitter() {
            return emitter.get();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for replay release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Replay wait was interrupted", exception);
            }
        }
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        private volatile Throwable error;

        @Override
        public void send(SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof Map<?, ?> map) {
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    map.forEach((key, value) -> payload.put(key.toString(), value));
                    payloads.add(Map.copyOf(payload));
                }
            }
        }

        @Override
        public void completeWithError(Throwable throwable) {
            error = throwable;
        }

        private List<Long> outputEventIds() {
            return payloads.stream()
                    .map(payload -> payload.get("eventId"))
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .map(Number::longValue)
                    .toList();
        }

        private String connectedStatus() {
            return payloads.stream()
                    .filter(payload -> payload.containsKey("runId"))
                    .map(payload -> payload.get("status").toString())
                    .findFirst()
                    .orElse(null);
        }

        private Throwable error() {
            return error;
        }
    }
}
