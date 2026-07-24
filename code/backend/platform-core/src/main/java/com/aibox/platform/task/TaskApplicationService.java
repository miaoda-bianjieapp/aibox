package com.aibox.platform.task;

import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.catalog.FeatureCatalogService;
import com.aibox.platform.catalog.FeatureDefinitionEntity;
import com.aibox.platform.common.ConflictException;
import com.aibox.platform.common.JsonCodec;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.execution.IdempotencyService;
import com.aibox.platform.execution.JobEntity;
import com.aibox.platform.execution.JobRepository;
import com.aibox.platform.execution.OutboxService;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.model.ModelCatalogService;
import com.aibox.platform.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class TaskApplicationService {

    private final TaskRepository taskRepository;
    private final TaskRunRepository runRepository;
    private final TaskPromptSummaryService promptSummaryService;
    private final JobRepository jobRepository;
    private final FeatureCatalogService catalogService;
    private final ProjectService projectService;
    private final ArtifactService artifactService;
    private final AssetService assetService;
    private final ModelCatalogService modelCatalogService;
    private final ActorContextProvider actorContextProvider;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final JsonCodec jsonCodec;
    private final Clock clock;

    public TaskApplicationService(
            TaskRepository taskRepository,
            TaskRunRepository runRepository,
            TaskPromptSummaryService promptSummaryService,
            JobRepository jobRepository,
            FeatureCatalogService catalogService,
            ProjectService projectService,
            ArtifactService artifactService,
            AssetService assetService,
            ModelCatalogService modelCatalogService,
            ActorContextProvider actorContextProvider,
            IdempotencyService idempotencyService,
            OutboxService outboxService,
            JsonCodec jsonCodec,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.promptSummaryService = promptSummaryService;
        this.jobRepository = jobRepository;
        this.catalogService = catalogService;
        this.projectService = projectService;
        this.artifactService = artifactService;
        this.assetService = assetService;
        this.modelCatalogService = modelCatalogService;
        this.actorContextProvider = actorContextProvider;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    @Transactional
    public TaskView createTask(UUID projectId, String featureCode, String title) {
        ActorContext actor = actorContextProvider.current();
        if (projectId != null) {
            projectService.requireOwned(projectId);
        }
        catalogService.requireVisibleFeature(featureCode);
        Instant now = clock.instant();
        TaskEntity task = new TaskEntity(
                UUID.randomUUID(),
                actor.tenantId(),
                actor.userId(),
                projectId,
                featureCode,
                title.trim(),
                now
        );
        taskRepository.save(task);
        outboxService.append("TASK", task.getId(), "TASK_CREATED", Map.of("featureCode", featureCode));
        return toTaskView(task);
    }

    @Transactional(readOnly = true)
    public List<TaskView> listTasks() {
        return listTasks(null, null);
    }

    @Transactional(readOnly = true)
    public List<TaskView> listTasks(String workspaceCode) {
        return listTasks(workspaceCode, null);
    }

    @Transactional(readOnly = true)
    public List<TaskView> listTasks(String workspaceCode, String keyword) {
        ActorContext actor = actorContextProvider.current();
        String normalizedWorkspaceCode = workspaceCode == null ? "" : workspaceCode.trim();
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedWorkspaceCode.length() > 80
                || (!normalizedWorkspaceCode.isEmpty()
                && !catalogService.isEnabledWorkspace(normalizedWorkspaceCode))) {
            throw new PlatformException(
                    "INVALID_WORKSPACE_FILTER",
                    "Workspace filter is invalid"
            );
        }
        if (normalizedKeyword.length() > 240) {
            throw new PlatformException(
                    "INVALID_TASK_SEARCH",
                    "Task search keyword is invalid"
            );
        }
        List<TaskEntity> tasks;
        if (normalizedWorkspaceCode.isEmpty() && normalizedKeyword.isEmpty()) {
            tasks = taskRepository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                    actor.tenantId(), actor.userId()
            );
        } else if (normalizedWorkspaceCode.isEmpty()) {
            tasks = taskRepository.findOwnedByTitleOrPromptKeyword(
                    actor.tenantId(), actor.userId(), normalizedKeyword
            );
        } else if (normalizedKeyword.isEmpty()) {
            tasks = taskRepository.findOwnedByWorkspace(
                    actor.tenantId(), actor.userId(), normalizedWorkspaceCode
            );
        } else {
            tasks = taskRepository.findOwnedByWorkspaceAndTitleOrPromptKeyword(
                    actor.tenantId(),
                    actor.userId(),
                    normalizedWorkspaceCode,
                    normalizedKeyword
            );
        }
        Map<UUID, Map<String, Object>> firstRunParameters = firstRunParameters(tasks, actor);
        return tasks.stream()
                .map(task -> toTaskView(
                        task,
                        promptSummaryService.snippet(
                                task.getFeatureCode(),
                                firstRunParameters.get(task.getId())
                        )
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDetailView getTask(UUID taskId) {
        TaskEntity task = requireOwnedTask(taskId);
        ActorContext actor = actorContextProvider.current();
        List<TaskRunEntity> runEntities = runRepository
                .findByTaskIdAndTenantIdAndUserIdOrderByRunNumberDesc(
                        taskId, actor.tenantId(), actor.userId()
                );
        List<RunView> runs = runEntities.stream()
                .map(this::toRunView)
                .toList();
        Map<String, Object> firstParameters = runEntities.isEmpty()
                ? Map.of()
                : runEntities.get(runEntities.size() - 1).getParameters();
        return new TaskDetailView(
                toTaskView(
                        task,
                        promptSummaryService.snippet(task.getFeatureCode(), firstParameters)
                ),
                runs,
                artifactService.listByTask(taskId)
        );
    }

    @Transactional
    public RunView createRun(
            UUID taskId,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            Map<String, String> selectedModels,
            String selectedModelCode,
            String idempotencyKey
    ) {
        ActorContext actor = actorContextProvider.current();
        TaskEntity task = taskRepository.findOwnedForUpdate(taskId, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("task", taskId));
        FeatureDefinitionEntity feature = catalogService.requireVisibleFeature(task.getFeatureCode());

        Map<String, Object> normalizedParameters = new TreeMap<>(parameters == null ? Map.of() : parameters);
        List<UUID> normalizedAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        assetService.requireOwnedAll(normalizedAssetIds);
        if (baseArtifactId != null) {
            artifactService.requireForTask(baseArtifactId, taskId, actor.tenantId(), actor.userId());
        }
        ModelCatalogService.ResolvedModels modelSelection = modelCatalogService.resolveForRun(
                task.getFeatureCode(), selectedModels, selectedModelCode
        );
        Map<String, String> resolvedModels = modelSelection.deployments();
        String resolvedModelCode = modelSelection.primaryDeploymentCode();
        UUID proposedRunId = UUID.randomUUID();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("taskId", taskId.toString());
        canonical.put("featureVersion", feature.getCurrentVersion());
        canonical.put("parameters", normalizedParameters);
        canonical.put("inputAssetIds", normalizedAssetIds.stream().map(UUID::toString).toList());
        canonical.put("baseArtifactId", baseArtifactId == null ? null : baseArtifactId.toString());
        canonical.put("selectedModels", selectedModels == null ? Map.of() : new TreeMap<>(selectedModels));
        canonical.put("selectedModelCode", selectedModelCode == null || selectedModelCode.isBlank()
                ? null : selectedModelCode.trim());

        Optional<UUID> existingRunId = idempotencyService.reserveOrResolve(
                actor.tenantId(),
                "CREATE_TASK_RUN:" + taskId,
                idempotencyKey,
                jsonCodec.write(canonical),
                proposedRunId
        );
        if (existingRunId.isPresent()) {
            return toRunView(requireOwnedRun(existingRunId.get()));
        }

        int runNumber = runRepository.findMaxRunNumber(taskId) + 1;
        Instant now = clock.instant();
        TaskRunEntity run = new TaskRunEntity(
                proposedRunId,
                actor.tenantId(),
                actor.userId(),
                taskId,
                runNumber,
                task.getFeatureCode(),
                feature.getCurrentVersion(),
                normalizedParameters,
                normalizedAssetIds,
                baseArtifactId,
                resolvedModelCode,
                resolvedModels,
                now
        );
        runRepository.save(run);
        jobRepository.save(new JobEntity(UUID.randomUUID(), actor.tenantId(), run.getId(), now));
        task.touch(now);
        outboxService.append("TASK_RUN", run.getId(), "TASK_RUN_QUEUED", Map.of("taskId", taskId));
        return toRunView(run);
    }

    @Transactional(readOnly = true)
    public RunDetailView getRun(UUID runId) {
        TaskRunEntity run = requireOwnedRun(runId);
        return new RunDetailView(toRunView(run), artifactService.listByRun(runId));
    }

    @Transactional
    public RunView cancelRun(UUID runId) {
        TaskRunEntity run = requireOwnedRun(runId);
        if (run.getStatus() == RunStatus.CANCELLED) {
            return toRunView(run);
        }
        if (run.getStatus().isTerminal() && run.getStatus() != RunStatus.CANCELLED) {
            throw new ConflictException("RUN_ALREADY_FINISHED", "A finished run cannot be cancelled");
        }
        run.cancel(clock.instant());
        jobRepository.cancelQueuedByRunId(runId);
        outboxService.append("TASK_RUN", runId, "TASK_RUN_CANCELLED", Map.of());
        return toRunView(run);
    }

    @Transactional
    public RunView retryRun(UUID runId, String idempotencyKey) {
        TaskRunEntity previous = requireOwnedRun(runId);
        return createRun(
                previous.getTaskId(),
                previous.getParameters(),
                previous.getInputAssetIds(),
                previous.getBaseArtifactId(),
                previous.getSelectedModels(),
                previous.getSelectedModelCode(),
                idempotencyKey
        );
    }

    @Transactional(readOnly = true)
    public TaskEntity requireOwnedTask(UUID taskId) {
        ActorContext actor = actorContextProvider.current();
        return taskRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        taskId,
                        actor.tenantId(),
                        actor.userId()
                )
                .orElseThrow(() -> new NotFoundException("task", taskId));
    }

    @Transactional(readOnly = true)
    public TaskRunEntity requireOwnedRun(UUID runId) {
        ActorContext actor = actorContextProvider.current();
        return runRepository.findByIdAndTenantIdAndUserId(runId, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("task run", runId));
    }

    private TaskView toTaskView(TaskEntity task) {
        return toTaskView(task, null);
    }

    private TaskView toTaskView(TaskEntity task, String promptSnippet) {
        return new TaskView(
                task.getId(),
                task.getProjectId(),
                task.getFeatureCode(),
                task.getTitle(),
                promptSnippet,
                task.getStatus(),
                task.getCurrentArtifactId(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private RunView toRunView(TaskRunEntity run) {
        return new RunView(
                run.getId(),
                run.getTaskId(),
                run.getRunNumber(),
                run.getFeatureCode(),
                run.getFeatureVersion(),
                run.getStatus(),
                run.getParameters(),
                run.getInputAssetIds(),
                run.getBaseArtifactId(),
                run.getSelectedModelCode(),
                run.getSelectedModels(),
                run.getErrorCode(),
                run.getErrorMessage(),
                run.getQueuedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt()
        );
    }

    public record TaskView(
            UUID id,
            UUID projectId,
            String featureCode,
            String title,
            String promptSnippet,
            TaskStatus status,
            UUID currentArtifactId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record RunView(
            UUID id,
            UUID taskId,
            int runNumber,
            String featureCode,
            int featureVersion,
            RunStatus status,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            String selectedModelCode,
            Map<String, String> selectedModels,
            String errorCode,
            String errorMessage,
            Instant queuedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt
    ) {
    }

    public record TaskDetailView(
            TaskView task,
            List<RunView> runs,
            List<ArtifactService.ArtifactView> artifacts
    ) {
    }

    public record RunDetailView(RunView run, List<ArtifactService.ArtifactView> artifacts) {
    }

    public RunView createRun(
            UUID taskId,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            String selectedModelCode,
            String idempotencyKey
    ) {
        return createRun(
                taskId, parameters, inputAssetIds, baseArtifactId,
                Map.of(), selectedModelCode, idempotencyKey
        );
    }

    public RunView createRun(
            UUID taskId,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            String idempotencyKey
    ) {
        return createRun(taskId, parameters, inputAssetIds, baseArtifactId, Map.of(), null, idempotencyKey);
    }

    private Map<UUID, Map<String, Object>> firstRunParameters(
            List<TaskEntity> tasks,
            ActorContext actor
    ) {
        if (tasks.isEmpty()) {
            return Map.of();
        }
        List<TaskRunEntity> firstRuns = runRepository.findFirstRunsByTaskIds(
                tasks.stream().map(TaskEntity::getId).toList(),
                actor.tenantId(),
                actor.userId()
        );
        Map<UUID, Map<String, Object>> parametersByTask = new HashMap<>();
        for (TaskRunEntity run : firstRuns) {
            parametersByTask.put(run.getTaskId(), run.getParameters());
        }
        return Map.copyOf(parametersByTask);
    }
}
