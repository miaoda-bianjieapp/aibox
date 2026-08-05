package com.aibox.platform.execution;

import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelProviderException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "yuanzuo.worker.enabled", havingValue = "true", matchIfMissing = true)
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final long POLL_ERROR_LOG_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final PostgresJobQueue jobQueue;
    private final RunExecutionCoordinator coordinator;
    private final RunExecutionStateService stateService;
    private final String workerId;
    private final Duration leaseDuration;
    private final Executor executor;
    private final ExecutorService managedExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Semaphore availableSlots;
    private final AtomicLong nextPollErrorLogAt = new AtomicLong();

    @Autowired
    public TaskWorker(
            PostgresJobQueue jobQueue,
            RunExecutionCoordinator coordinator,
            RunExecutionStateService stateService,
            @Value("${yuanzuo.worker.max-concurrency:20}") int maxConcurrency,
            @Value("${yuanzuo.worker.lease-seconds:300}") long leaseSeconds
    ) {
        this(
                jobQueue,
                coordinator,
                stateService,
                Math.max(1, maxConcurrency),
                Duration.ofSeconds(Math.max(30, leaseSeconds)),
                null,
                null
        );
    }

    public TaskWorker(
            PostgresJobQueue jobQueue,
            RunExecutionCoordinator coordinator,
            RunExecutionStateService stateService
    ) {
        this(jobQueue, coordinator, stateService, 1, Duration.ofMinutes(5), Runnable::run, null);
    }

    TaskWorker(
            PostgresJobQueue jobQueue,
            RunExecutionCoordinator coordinator,
            RunExecutionStateService stateService,
            int maxConcurrency,
            Duration leaseDuration,
            Executor executor,
            ScheduledExecutorService heartbeatExecutor
    ) {
        this.jobQueue = jobQueue;
        this.coordinator = coordinator;
        this.stateService = stateService;
        this.workerId = resolveWorkerId();
        this.leaseDuration = leaseDuration;
        this.availableSlots = new Semaphore(maxConcurrency);
        if (executor == null) {
            this.managedExecutor = Executors.newFixedThreadPool(
                    maxConcurrency,
                    runnable -> {
                        Thread thread = new Thread(runnable, "feature-worker");
                        thread.setDaemon(true);
                        return thread;
                    }
            );
            this.executor = managedExecutor;
        } else {
            this.managedExecutor = null;
            this.executor = executor;
        }
        this.heartbeatExecutor = heartbeatExecutor == null && managedExecutor != null
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "feature-worker-lease");
                    thread.setDaemon(true);
                    return thread;
                })
                : heartbeatExecutor;
    }

    @Scheduled(fixedDelayString = "${yuanzuo.worker.poll-delay-ms:500}")
    public void poll() {
        try {
            jobQueue.recoverExpiredLeases().forEach(runId ->
                    stateService.fail(
                            runId,
                            "WORKER_LEASE_EXHAUSTED",
                            "Worker lease expired after maximum attempts"
                    )
            );
            if (!availableSlots.tryAcquire()) return;
            var lease = jobQueue.claimNext(workerId, leaseDuration);
            if (lease.isEmpty()) {
                availableSlots.release();
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        execute(lease.get());
                    } finally {
                        availableSlots.release();
                    }
                });
            } catch (RuntimeException exception) {
                availableSlots.release();
                jobQueue.markFailed(
                        lease.get(),
                        workerId,
                        "Worker executor rejected the claimed job",
                        true
                );
                throw exception;
            }
            nextPollErrorLogAt.set(0L);
        } catch (RuntimeException exception) {
            logPollFailure(exception);
        }
    }

    private void execute(PostgresJobQueue.JobLease lease) {
        LeaseGuard leaseGuard = startLeaseGuard(lease);
        try {
            coordinator.execute(lease.runId());
            PostgresJobQueue.LeaseRenewal completion = jobQueue.renewLease(
                    lease, workerId, leaseDuration
            );
            if (completion == PostgresJobQueue.LeaseRenewal.RUN_CANCELLED) {
                jobQueue.markCancelled(lease, workerId);
            } else if (completion == PostgresJobQueue.LeaseRenewal.RENEWED
                    && leaseGuard.state() == LeaseState.ACTIVE) {
                jobQueue.markSucceeded(lease, workerId);
            }
        } catch (RunCancelledException exception) {
            log.info("Feature execution stopped for cancelled run {}", lease.runId());
            if (leaseGuard.state() != LeaseState.LOST) {
                jobQueue.markCancelled(lease, workerId);
            }
        } catch (FeatureValidationException exception) {
            if (leaseGuard.handleTermination()) return;
            log.info("Feature validation failed for run {}: {}", lease.runId(), exception.getMessage());
            stateService.fail(lease.runId(), "FEATURE_VALIDATION_FAILED", exception.getMessage());
            jobQueue.markFailed(lease, workerId, exception.getMessage(), false);
        } catch (ModelProviderException exception) {
            if (leaseGuard.handleTermination()) return;
            boolean retryable = exception.retryable() && lease.attempts() < lease.maxAttempts();
            log.warn(
                    "Model provider failed for run {}, code={}, retryable={}",
                    lease.runId(),
                    exception.code(),
                    retryable
            );
            if (!retryable) {
                stateService.fail(lease.runId(), exception.code(), exception.getMessage());
            }
            jobQueue.markFailed(lease, workerId, exception.getMessage(), retryable);
        } catch (RuntimeException exception) {
            if (leaseGuard.handleTermination()) return;
            boolean retryable = lease.attempts() < lease.maxAttempts();
            log.error("Feature execution failed for run {}, retryable={}", lease.runId(), retryable, exception);
            if (!retryable) {
                stateService.fail(lease.runId(), "FEATURE_EXECUTION_FAILED", exception.getMessage());
            }
            jobQueue.markFailed(lease, workerId, exception.getMessage(), retryable);
        } finally {
            leaseGuard.close();
        }
    }

    private LeaseGuard startLeaseGuard(PostgresJobQueue.JobLease lease) {
        if (heartbeatExecutor == null) return new LeaseGuard();
        LeaseGuard guard = new LeaseGuard(lease, Thread.currentThread());
        long heartbeatMillis = Math.max(
                1_000,
                Math.min(5_000, leaseDuration.toMillis() / 3)
        );
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                guard::renew,
                heartbeatMillis,
                heartbeatMillis,
                TimeUnit.MILLISECONDS
        );
        guard.attach(heartbeat);
        return guard;
    }

    @PreDestroy
    void shutdown() {
        if (managedExecutor != null) managedExecutor.shutdownNow();
        if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();
    }

    private static String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (UnknownHostException exception) {
            return "worker:" + UUID.randomUUID();
        }
    }

    private void logPollFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long next = nextPollErrorLogAt.get();
        if (now < next || !nextPollErrorLogAt.compareAndSet(next, now + POLL_ERROR_LOG_INTERVAL_MILLIS)) {
            return;
        }
        log.error("Worker polling failed; repeated errors will be suppressed for 30 seconds", exception);
    }

    private final class LeaseGuard implements AutoCloseable {
        private final PostgresJobQueue.JobLease lease;
        private final Thread executionThread;
        private final AtomicReference<LeaseState> state;
        private ScheduledFuture<?> heartbeat;

        private LeaseGuard(PostgresJobQueue.JobLease lease, Thread executionThread) {
            this.lease = lease;
            this.executionThread = executionThread;
            this.state = new AtomicReference<>(LeaseState.ACTIVE);
        }

        private LeaseGuard() {
            this.lease = null;
            this.executionThread = null;
            this.state = new AtomicReference<>(LeaseState.ACTIVE);
        }

        void attach(ScheduledFuture<?> heartbeat) {
            this.heartbeat = heartbeat;
        }

        void renew() {
            try {
                PostgresJobQueue.LeaseRenewal renewal = jobQueue.renewLease(
                        lease, workerId, leaseDuration
                );
                if (renewal == PostgresJobQueue.LeaseRenewal.RENEWED) return;
                state.compareAndSet(
                        LeaseState.ACTIVE,
                        renewal == PostgresJobQueue.LeaseRenewal.RUN_CANCELLED
                                ? LeaseState.RUN_CANCELLED
                                : LeaseState.LOST
                );
                executionThread.interrupt();
            } catch (RuntimeException exception) {
                log.warn("Could not renew worker lease for run {}", lease.runId(), exception);
                state.compareAndSet(LeaseState.ACTIVE, LeaseState.LOST);
                executionThread.interrupt();
            }
        }

        LeaseState state() {
            return state.get();
        }

        boolean handleTermination() {
            if (state() == LeaseState.RUN_CANCELLED) {
                jobQueue.markCancelled(lease, workerId);
                return true;
            }
            if (state() == LeaseState.LOST) {
                log.warn("Stopped local execution after losing the lease for run {}", lease.runId());
                return true;
            }
            return false;
        }

        @Override
        public void close() {
            if (heartbeat != null) heartbeat.cancel(false);
            Thread.interrupted();
        }
    }

    private enum LeaseState {
        ACTIVE,
        RUN_CANCELLED,
        LOST
    }
}
