package com.aibox.platform.execution;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.platform.artifact.ArtifactEntity;
import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.task.RunStatus;
import com.aibox.platform.task.TaskRunEntity;
import com.aibox.platform.task.TaskRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RunExecutionStateService {

    private final TaskRunRepository runRepository;
    private final ArtifactService artifactService;
    private final OutboxService outboxService;
    private final RunEventPublisher eventPublisher;
    private final Clock clock;

    public RunExecutionStateService(
            TaskRunRepository runRepository,
            ArtifactService artifactService,
            OutboxService outboxService,
            RunEventPublisher eventPublisher,
            Clock clock
    ) {
        this.runRepository = runRepository;
        this.artifactService = artifactService;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Optional<FeatureExecutionContext> start(UUID runId) {
        TaskRunEntity run = requireRun(runId);
        if (run.getStatus().isTerminal()) {
            return Optional.empty();
        }
        if (run.getStatus() == RunStatus.QUEUED) {
            run.markRunning(clock.instant());
            outboxService.append("TASK_RUN", runId, "TASK_RUN_STARTED", Map.of());
            publishAfterCommit(runId, "status", Map.of("status", RunStatus.RUNNING.name()));
        } else if (run.getStatus() != RunStatus.RUNNING) {
            throw new IllegalStateException("Run cannot execute from status " + run.getStatus());
        }

        return Optional.of(new FeatureExecutionContext(
                run.getTenantId(),
                run.getUserId(),
                run.getTaskId(),
                run.getId(),
                run.getFeatureCode(),
                run.getFeatureVersion(),
                run.getParameters(),
                run.getInputAssetIds(),
                run.getSelectedModels(),
                run.getSelectedModelCode(),
                artifactService.baseReference(run)
        ));
    }

    @Transactional
    public void succeed(UUID runId, List<ArtifactDraft> drafts) {
        TaskRunEntity run = requireRun(runId);
        if (run.getStatus() == RunStatus.CANCELLED) {
            return;
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new IllegalStateException("Run cannot succeed from status " + run.getStatus());
        }
        List<ArtifactEntity> artifacts = artifactService.saveRunArtifacts(run, drafts);
        run.markSucceeded(clock.instant());
        outboxService.append(
                "TASK_RUN",
                runId,
                "TASK_RUN_SUCCEEDED",
                Map.of("artifactIds", artifacts.stream().map(item -> item.getId().toString()).toList())
        );
        publishAfterCommit(
                runId,
                "completed",
                Map.of(
                        "status", RunStatus.SUCCEEDED.name(),
                        "artifactIds", artifacts.stream().map(item -> item.getId().toString()).toList()
                )
        );
    }

    @Transactional
    public void fail(UUID runId, String errorCode, String errorMessage) {
        TaskRunEntity run = requireRun(runId);
        if (run.getStatus().isTerminal()) {
            return;
        }
        run.markFailed(errorCode, abbreviate(errorMessage), clock.instant());
        outboxService.append(
                "TASK_RUN",
                runId,
                "TASK_RUN_FAILED",
                Map.of("errorCode", errorCode, "errorMessage", abbreviate(errorMessage))
        );
        publishAfterCommit(
                runId,
                "failed",
                Map.of("status", RunStatus.FAILED.name(), "errorCode", errorCode)
        );
    }

    private TaskRunEntity requireRun(UUID runId) {
        return runRepository.findById(runId).orElseThrow(() -> new NotFoundException("task run", runId));
    }

    private void publishAfterCommit(UUID runId, String eventType, Map<String, Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            eventPublisher.publish(runId, eventType, data);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publish(runId, eventType, data);
            }
        });
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown execution error";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
