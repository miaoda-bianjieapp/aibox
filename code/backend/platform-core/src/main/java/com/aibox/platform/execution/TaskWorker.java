package com.aibox.platform.execution;

import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "yuanzuo.worker.enabled", havingValue = "true", matchIfMissing = true)
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final long POLL_ERROR_LOG_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final PostgresJobQueue jobQueue;
    private final RunExecutionCoordinator coordinator;
    private final RunExecutionStateService stateService;
    private final String workerId;
    private final AtomicLong nextPollErrorLogAt = new AtomicLong();

    public TaskWorker(
            PostgresJobQueue jobQueue,
            RunExecutionCoordinator coordinator,
            RunExecutionStateService stateService
    ) {
        this.jobQueue = jobQueue;
        this.coordinator = coordinator;
        this.stateService = stateService;
        this.workerId = resolveWorkerId();
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
            jobQueue.claimNext(workerId, LEASE_DURATION).ifPresent(this::execute);
            nextPollErrorLogAt.set(0L);
        } catch (RuntimeException exception) {
            logPollFailure(exception);
        }
    }

    private void execute(PostgresJobQueue.JobLease lease) {
        try {
            coordinator.execute(lease.runId());
            jobQueue.markSucceeded(lease.jobId());
        } catch (FeatureValidationException exception) {
            log.info("Feature validation failed for run {}: {}", lease.runId(), exception.getMessage());
            stateService.fail(lease.runId(), "FEATURE_VALIDATION_FAILED", exception.getMessage());
            jobQueue.markFailed(lease, exception.getMessage(), false);
        } catch (ModelProviderException exception) {
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
            jobQueue.markFailed(lease, exception.getMessage(), retryable);
        } catch (RuntimeException exception) {
            boolean retryable = lease.attempts() < lease.maxAttempts();
            log.error("Feature execution failed for run {}, retryable={}", lease.runId(), retryable, exception);
            if (!retryable) {
                stateService.fail(lease.runId(), "FEATURE_EXECUTION_FAILED", exception.getMessage());
            }
            jobQueue.markFailed(lease, exception.getMessage(), retryable);
        }
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
}
