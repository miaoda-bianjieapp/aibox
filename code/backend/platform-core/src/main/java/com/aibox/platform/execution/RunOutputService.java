package com.aibox.platform.execution;

import com.aibox.feature.spi.FeatureOutputEmitter;
import com.aibox.platform.common.JsonCodec;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.task.TaskRunRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Service
public class RunOutputService {

    private static final int FLUSH_CHARACTER_THRESHOLD = 256;
    private static final long FLUSH_DELAY_MILLIS = 75;
    private static final long CANCEL_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final Duration EVENT_RETENTION = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;
    private final TaskRunRepository runRepository;
    private final ActorContextProvider actorContextProvider;
    private final RunEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ScheduledExecutorService flushExecutor;

    public RunOutputService(
            JdbcTemplate jdbcTemplate,
            JsonCodec jsonCodec,
            TaskRunRepository runRepository,
            ActorContextProvider actorContextProvider,
            RunEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
        this.runRepository = runRepository;
        this.actorContextProvider = actorContextProvider;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "run-output-flush");
            thread.setDaemon(true);
            return thread;
        };
        this.flushExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public FeatureOutputEmitter emitter(UUID runId) {
        return new PersistentFeatureOutputEmitter(runId);
    }

    public List<RunOutputStreamView> getOwnedStreams(UUID runId) {
        requireOwnedRun(runId);
        return jdbcTemplate.query("""
                select run_id, channel, format, content_text, status, last_sequence, updated_at
                from run_output_stream
                where run_id = ?
                order by channel
                """, (resultSet, rowNumber) -> new RunOutputStreamView(
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("channel"),
                resultSet.getString("format"),
                resultSet.getString("content_text"),
                resultSet.getString("status"),
                resultSet.getLong("last_sequence"),
                resultSet.getTimestamp("updated_at").toInstant()
        ), runId);
    }

    public List<RunOutputEventView> getOwnedEventsAfter(UUID runId, long lastEventId) {
        requireOwnedRun(runId);
        Instant retentionStart = clock.instant().minus(EVENT_RETENTION);
        return jdbcTemplate.query("""
                select id, run_id, channel, sequence, event_type, payload_json::text, created_at
                from run_output_event
                where run_id = ? and id > ? and created_at >= ?
                order by id
                limit 1000
                """, (resultSet, rowNumber) -> new RunOutputEventView(
                resultSet.getLong("id"),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getString("channel"),
                resultSet.getLong("sequence"),
                resultSet.getString("event_type"),
                jsonCodec.readMap(resultSet.getString("payload_json")),
                resultSet.getTimestamp("created_at").toInstant()
        ), runId, lastEventId, Timestamp.from(retentionStart));
    }

    public String currentMainText(UUID runId) {
        List<String> values = jdbcTemplate.query("""
                select content_text
                from run_output_stream
                where run_id = ? and channel = 'main'
                """, (resultSet, rowNumber) -> resultSet.getString(1), runId);
        return values.isEmpty() ? "" : values.get(0);
    }

    public void failRun(UUID runId) {
        List<RunOutputEventView> events = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<StreamState> streams = jdbcTemplate.query("""
                    select channel, format, content_text, status, last_sequence
                    from run_output_stream
                    where run_id = ?
                    for update
                    """, (resultSet, rowNumber) -> new StreamState(
                    resultSet.getString("channel"),
                    resultSet.getString("format"),
                    resultSet.getString("content_text"),
                    resultSet.getString("status"),
                    resultSet.getLong("last_sequence")
            ), runId);
            List<RunOutputEventView> failedEvents = new ArrayList<>();
            for (StreamState stream : streams) {
                if (!"STREAMING".equals(stream.status())) continue;
                long sequence = stream.lastSequence() + 1;
                jdbcTemplate.update("""
                        update run_output_stream
                        set status = 'FAILED', last_sequence = ?, updated_at = ?
                        where run_id = ? and channel = ?
                        """, sequence, Timestamp.from(now), runId, stream.channel());
                Map<String, Object> payload = outputPayload(
                        stream.channel(), sequence, "failed", Map.of("status", "FAILED")
                );
                failedEvents.add(insertEvent(runId, stream.channel(), sequence, "failed", payload, now));
            }
            return failedEvents;
        });
        publish(events);
    }

    private RunOutputEventView start(UUID runId, String channel, String format) {
        RunOutputEventView event = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            jdbcTemplate.update(
                    "delete from run_output_event where created_at < ?",
                    Timestamp.from(now.minus(EVENT_RETENTION))
            );
            ensureStream(runId, channel, format, now);
            StreamState stream = lockStream(runId, channel);
            if ("COMPLETED".equals(stream.status())) return null;
            long sequence = stream.lastSequence() + 1;
            jdbcTemplate.update("""
                    update run_output_stream
                    set format = ?, content_text = '', status = 'STREAMING',
                        last_sequence = ?, updated_at = ?
                    where run_id = ? and channel = ?
                    """, format, sequence, Timestamp.from(now), runId, channel);
            Map<String, Object> payload = outputPayload(
                    channel, sequence, "started",
                    Map.of("format", format, "content", "", "status", "STREAMING")
            );
            return insertEvent(runId, channel, sequence, "started", payload, now);
        });
        publish(event);
        return event;
    }

    private RunOutputEventView append(UUID runId, String channel, String delta) {
        if (delta == null || delta.isEmpty()) return null;
        RunOutputEventView event = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            ensureStream(runId, channel, "text", now);
            StreamState stream = lockStream(runId, channel);
            if (!"STREAMING".equals(stream.status())) return null;
            long sequence = stream.lastSequence() + 1;
            jdbcTemplate.update("""
                    update run_output_stream
                    set content_text = content_text || ?, last_sequence = ?, updated_at = ?
                    where run_id = ? and channel = ?
                    """, delta, sequence, Timestamp.from(now), runId, channel);
            Map<String, Object> payload = outputPayload(
                    channel, sequence, "append", Map.of("delta", delta, "status", "STREAMING")
            );
            return insertEvent(runId, channel, sequence, "append", payload, now);
        });
        publish(event);
        return event;
    }

    private RunOutputEventView replace(UUID runId, String channel, String content) {
        String normalized = content == null ? "" : content;
        RunOutputEventView event = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            ensureStream(runId, channel, "text", now);
            StreamState stream = lockStream(runId, channel);
            if (!"STREAMING".equals(stream.status())) return null;
            long sequence = stream.lastSequence() + 1;
            jdbcTemplate.update("""
                    update run_output_stream
                    set content_text = ?, last_sequence = ?, updated_at = ?
                    where run_id = ? and channel = ?
                    """, normalized, sequence, Timestamp.from(now), runId, channel);
            Map<String, Object> payload = outputPayload(
                    channel, sequence, "replace", Map.of("content", normalized, "status", "STREAMING")
            );
            return insertEvent(runId, channel, sequence, "replace", payload, now);
        });
        publish(event);
        return event;
    }

    private RunOutputEventView complete(UUID runId, String channel) {
        RunOutputEventView event = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            ensureStream(runId, channel, "text", now);
            StreamState stream = lockStream(runId, channel);
            if ("COMPLETED".equals(stream.status())) return null;
            long sequence = stream.lastSequence() + 1;
            jdbcTemplate.update("""
                    update run_output_stream
                    set status = 'COMPLETED', last_sequence = ?, updated_at = ?
                    where run_id = ? and channel = ?
                    """, sequence, Timestamp.from(now), runId, channel);
            Map<String, Object> payload = outputPayload(
                    channel, sequence, "completed", Map.of("status", "COMPLETED")
            );
            return insertEvent(runId, channel, sequence, "completed", payload, now);
        });
        publish(event);
        return event;
    }

    private RunOutputEventView partial(UUID runId, String channel) {
        RunOutputEventView event = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            ensureStream(runId, channel, "text", now);
            StreamState stream = lockStream(runId, channel);
            if (!"STREAMING".equals(stream.status())) return null;
            long sequence = stream.lastSequence() + 1;
            jdbcTemplate.update("""
                    update run_output_stream
                    set status = 'PARTIAL', last_sequence = ?, updated_at = ?
                    where run_id = ? and channel = ?
                    """, sequence, Timestamp.from(now), runId, channel);
            Map<String, Object> payload = outputPayload(
                    channel, sequence, "partial", Map.of("status", "PARTIAL")
            );
            return insertEvent(runId, channel, sequence, "partial", payload, now);
        });
        publish(event);
        return event;
    }

    private void ensureStream(UUID runId, String channel, String format, Instant now) {
        jdbcTemplate.update("""
                insert into run_output_stream (
                    run_id, channel, format, content_text, status, last_sequence, created_at, updated_at
                ) values (?, ?, ?, '', 'STREAMING', 0, ?, ?)
                on conflict (run_id, channel) do nothing
                """, runId, channel, format, Timestamp.from(now), Timestamp.from(now));
    }

    private StreamState lockStream(UUID runId, String channel) {
        return jdbcTemplate.queryForObject("""
                select channel, format, content_text, status, last_sequence
                from run_output_stream
                where run_id = ? and channel = ?
                for update
                """, (resultSet, rowNumber) -> new StreamState(
                resultSet.getString("channel"),
                resultSet.getString("format"),
                resultSet.getString("content_text"),
                resultSet.getString("status"),
                resultSet.getLong("last_sequence")
        ), runId, channel);
    }

    private RunOutputEventView insertEvent(
            UUID runId,
            String channel,
            long sequence,
            String eventType,
            Map<String, Object> payload,
            Instant now
    ) {
        Long id = jdbcTemplate.queryForObject("""
                insert into run_output_event (
                    run_id, channel, sequence, event_type, payload_json, created_at
                ) values (?, ?, ?, ?, cast(? as jsonb), ?)
                returning id
                """, Long.class, runId, channel, sequence, eventType, jsonCodec.write(payload), Timestamp.from(now));
        return new RunOutputEventView(
                id == null ? 0 : id, runId, channel, sequence, eventType, payload, now
        );
    }

    private boolean isCancelled(UUID runId) {
        List<Boolean> values = jdbcTemplate.query("""
                select cancel_requested or status = 'CANCELLED'
                from task_run
                where id = ?
                """, (resultSet, rowNumber) -> resultSet.getBoolean(1), runId);
        return values.isEmpty() || values.get(0);
    }

    private void requireOwnedRun(UUID runId) {
        ActorContext actor = actorContextProvider.current();
        if (runRepository.findByIdAndTenantIdAndUserId(runId, actor.tenantId(), actor.userId()).isEmpty()) {
            throw new NotFoundException("task run", runId);
        }
    }

    private void publish(RunOutputEventView event) {
        if (event == null) return;
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        data.put("eventId", event.id());
        eventPublisher.publish(event.runId(), "output", Map.copyOf(data));
    }

    private void publish(List<RunOutputEventView> events) {
        if (events == null) return;
        events.forEach(this::publish);
    }

    private static Map<String, Object> outputPayload(
            String channel,
            long sequence,
            String type,
            Map<String, Object> values
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("sequence", sequence);
        payload.put("type", type);
        payload.putAll(values);
        return Map.copyOf(payload);
    }

    @PreDestroy
    void shutdown() {
        flushExecutor.shutdownNow();
    }

    public record RunOutputStreamView(
            UUID runId,
            String channel,
            String format,
            String content,
            String status,
            long lastSequence,
            Instant updatedAt
    ) {
    }

    public record RunOutputEventView(
            long id,
            UUID runId,
            String channel,
            long sequence,
            String eventType,
            Map<String, Object> data,
            Instant createdAt
    ) {
    }

    private record StreamState(
            String channel,
            String format,
            String content,
            String status,
            long lastSequence
    ) {
    }

    private final class PersistentFeatureOutputEmitter implements FeatureOutputEmitter {

        private final UUID runId;
        private final Map<String, ChannelBuffer> channels = new ConcurrentHashMap<>();
        private volatile boolean cancelled;
        private volatile long nextCancellationCheckNanos;

        private PersistentFeatureOutputEmitter(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void start(String channel, String format) {
            ChannelBuffer buffer = channels.computeIfAbsent(channel, ChannelBuffer::new);
            synchronized (buffer) {
                if (buffer.started) return;
                RunOutputService.this.start(runId, channel, format);
                buffer.started = true;
            }
        }

        @Override
        public void appendText(String channel, String delta) {
            if (delta == null || delta.isEmpty()) return;
            ChannelBuffer buffer = channels.computeIfAbsent(channel, ChannelBuffer::new);
            synchronized (buffer) {
                if (buffer.completed) return;
                if (!buffer.started) {
                    RunOutputService.this.start(runId, channel, "text");
                    buffer.started = true;
                }
                buffer.pending.append(delta);
                if (buffer.pending.length() >= FLUSH_CHARACTER_THRESHOLD) {
                    flush(buffer);
                } else if (buffer.scheduledFlush == null) {
                    buffer.scheduledFlush = flushExecutor.schedule(
                            () -> flushScheduled(buffer),
                            FLUSH_DELAY_MILLIS,
                            TimeUnit.MILLISECONDS
                    );
                }
            }
        }

        @Override
        public void replaceText(String channel, String content) {
            ChannelBuffer buffer = channels.computeIfAbsent(channel, ChannelBuffer::new);
            synchronized (buffer) {
                if (buffer.completed) return;
                if (!buffer.started) {
                    RunOutputService.this.start(runId, channel, "text");
                    buffer.started = true;
                }
                cancelScheduled(buffer);
                buffer.pending.setLength(0);
                RunOutputService.this.replace(runId, channel, content);
            }
        }

        @Override
        public void complete(String channel) {
            ChannelBuffer buffer = channels.computeIfAbsent(channel, ChannelBuffer::new);
            synchronized (buffer) {
                if (buffer.completed) return;
                if (!buffer.started) {
                    RunOutputService.this.start(runId, channel, "text");
                    buffer.started = true;
                }
                cancelScheduled(buffer);
                flush(buffer);
                RunOutputService.this.complete(runId, channel);
                buffer.completed = true;
            }
        }

        @Override
        public void completeAll() {
            if (isCancelled(true)) {
                channels.keySet().forEach(this::partial);
                return;
            }
            channels.keySet().forEach(this::complete);
        }

        @Override
        public boolean isCancelled() {
            return isCancelled(false);
        }

        private boolean isCancelled(boolean force) {
            if (cancelled) return true;
            long now = System.nanoTime();
            if (!force && now < nextCancellationCheckNanos) return false;
            cancelled = RunOutputService.this.isCancelled(runId);
            nextCancellationCheckNanos = now + CANCEL_CHECK_INTERVAL_NANOS;
            return cancelled;
        }

        private void flushScheduled(ChannelBuffer buffer) {
            synchronized (buffer) {
                buffer.scheduledFlush = null;
                flush(buffer);
            }
        }

        private void partial(String channel) {
            ChannelBuffer buffer = channels.computeIfAbsent(channel, ChannelBuffer::new);
            synchronized (buffer) {
                if (buffer.completed) return;
                if (!buffer.started) {
                    RunOutputService.this.start(runId, channel, "text");
                    buffer.started = true;
                }
                cancelScheduled(buffer);
                flush(buffer);
                RunOutputService.this.partial(runId, channel);
                buffer.completed = true;
            }
        }

        private void flush(ChannelBuffer buffer) {
            cancelScheduled(buffer);
            if (buffer.pending.isEmpty()) return;
            String delta = buffer.pending.toString();
            buffer.pending.setLength(0);
            RunOutputService.this.append(runId, buffer.channel, delta);
        }

        private void cancelScheduled(ChannelBuffer buffer) {
            ScheduledFuture<?> scheduled = buffer.scheduledFlush;
            buffer.scheduledFlush = null;
            if (scheduled != null) scheduled.cancel(false);
        }
    }

    private static final class ChannelBuffer {
        private final String channel;
        private final StringBuilder pending = new StringBuilder();
        private ScheduledFuture<?> scheduledFlush;
        private boolean started;
        private boolean completed;

        private ChannelBuffer(String channel) {
            this.channel = channel;
        }
    }
}
