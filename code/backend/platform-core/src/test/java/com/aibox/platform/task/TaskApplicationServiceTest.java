package com.aibox.platform.task;

import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.catalog.FeatureCatalogService;
import com.aibox.platform.catalog.FeatureDefinitionEntity;
import com.aibox.platform.common.JsonCodec;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.execution.IdempotencyService;
import com.aibox.platform.execution.JobRepository;
import com.aibox.platform.execution.OutboxService;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.model.ModelCatalogService;
import com.aibox.platform.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskApplicationServiceTest {

    @Test
    void listsAllTasksWhenWorkspaceFilterIsBlank() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task("writing.draft");
        when(fixture.taskRepository
                .findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                        fixture.tenantId, fixture.userId
                ))
                .thenReturn(List.of(task));

        List<TaskApplicationService.TaskView> tasks = fixture.service.listTasks("  ");

        assertThat(tasks).extracting(TaskApplicationService.TaskView::featureCode)
                .containsExactly("writing.draft");
        verify(fixture.catalogService, never()).isEnabledWorkspace("writing");
        verify(fixture.taskRepository, never())
                .findOwnedByWorkspace(fixture.tenantId, fixture.userId, "writing");
    }

    @Test
    void filtersTasksByEnabledWorkspace() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task("image.generate");
        when(fixture.catalogService.isEnabledWorkspace("image")).thenReturn(true);
        when(fixture.taskRepository.findOwnedByWorkspace(
                fixture.tenantId, fixture.userId, "image"
        )).thenReturn(List.of(task));

        List<TaskApplicationService.TaskView> tasks = fixture.service.listTasks(" image ");

        assertThat(tasks).extracting(TaskApplicationService.TaskView::featureCode)
                .containsExactly("image.generate");
        verify(fixture.taskRepository)
                .findOwnedByWorkspace(fixture.tenantId, fixture.userId, "image");
    }

    @Test
    void rejectsUnknownWorkspaceFilter() {
        Fixture fixture = new Fixture();
        when(fixture.catalogService.isEnabledWorkspace("unknown")).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.listTasks("unknown"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_WORKSPACE_FILTER")
                );
        verify(fixture.taskRepository, never())
                .findOwnedByWorkspace(fixture.tenantId, fixture.userId, "unknown");
    }

    @Test
    void searchesAllTasksByTitle() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task("writing.draft");
        when(fixture.taskRepository.findOwnedByTitleOrPromptKeyword(
                fixture.tenantId, fixture.userId, "周报"
        )).thenReturn(List.of(task));

        List<TaskApplicationService.TaskView> tasks =
                fixture.service.listTasks(null, " 周报 ");

        assertThat(tasks).extracting(TaskApplicationService.TaskView::title)
                .containsExactly("Task title");
        verify(fixture.taskRepository).findOwnedByTitleOrPromptKeyword(
                fixture.tenantId, fixture.userId, "周报"
        );
    }

    @Test
    void combinesWorkspaceAndTitleSearch() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task("image.expand");
        when(fixture.catalogService.isEnabledWorkspace("image")).thenReturn(true);
        when(fixture.taskRepository.findOwnedByWorkspaceAndTitleOrPromptKeyword(
                fixture.tenantId, fixture.userId, "image", "扩图"
        )).thenReturn(List.of(task));

        List<TaskApplicationService.TaskView> tasks =
                fixture.service.listTasks("image", "扩图");

        assertThat(tasks).extracting(TaskApplicationService.TaskView::featureCode)
                .containsExactly("image.expand");
        verify(fixture.taskRepository).findOwnedByWorkspaceAndTitleOrPromptKeyword(
                fixture.tenantId, fixture.userId, "image", "扩图"
        );
    }

    @Test
    void includesPromptSnippetFromTheFirstRun() {
        Fixture fixture = new Fixture();
        TaskEntity task = fixture.task("writing.draft");
        when(fixture.taskRepository
                .findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                        fixture.tenantId, fixture.userId
                ))
                .thenReturn(List.of(task));
        when(fixture.taskRunRepository.findFirstRunsByTaskIds(
                List.of(task.getId()), fixture.tenantId, fixture.userId
        )).thenReturn(List.of(fixture.firstRun(task, "AI 产品周报")));

        List<TaskApplicationService.TaskView> tasks = fixture.service.listTasks();

        assertThat(tasks).extracting(TaskApplicationService.TaskView::promptSnippet)
                .containsExactly("AI 产品周报");
    }

    @Test
    void rejectsAnOversizedSearchKeyword() {
        Fixture fixture = new Fixture();
        String keyword = "a".repeat(241);

        assertThatThrownBy(() -> fixture.service.listTasks(null, keyword))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_TASK_SEARCH")
                );
    }

    @Test
    void flushesRunBeforeRecordingInputAssetSnapshots() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-24T04:00:00Z");

        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        JobRepository jobRepository = mock(JobRepository.class);
        FeatureCatalogService catalogService = mock(FeatureCatalogService.class);
        ProjectService projectService = mock(ProjectService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        AssetService assetService = mock(AssetService.class);
        ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        IdempotencyService idempotencyService = mock(IdempotencyService.class);
        OutboxService outboxService = mock(OutboxService.class);
        JsonCodec jsonCodec = mock(JsonCodec.class);

        TaskEntity task = new TaskEntity(
                taskId,
                tenantId,
                userId,
                null,
                "image.generate",
                "生成图片",
                now
        );
        FeatureDefinitionEntity feature = mock(FeatureDefinitionEntity.class);
        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(taskRepository.findOwnedForUpdate(taskId, tenantId, userId))
                .thenReturn(Optional.of(task));
        when(catalogService.requireVisibleFeature("image.generate")).thenReturn(feature);
        when(feature.getCurrentVersion()).thenReturn(1);
        when(modelCatalogService.resolveForRun("image.generate", Map.of(), null))
                .thenReturn(new ModelCatalogService.ResolvedModels(Map.of(), null));
        when(jsonCodec.write(any())).thenReturn("{}");
        when(idempotencyService.reserveOrResolve(
                eq(tenantId),
                eq("CREATE_TASK_RUN:" + taskId),
                eq("run-key"),
                eq("{}"),
                any(UUID.class)
        )).thenReturn(Optional.empty());
        when(runRepository.findMaxRunNumber(taskId)).thenReturn(0);
        when(assetService.listRunInputs(any(UUID.class))).thenReturn(List.of());

        TaskApplicationService service = new TaskApplicationService(
                taskRepository,
                runRepository,
                new TaskPromptSummaryService(),
                jobRepository,
                catalogService,
                projectService,
                artifactService,
                assetService,
                modelCatalogService,
                actorContextProvider,
                idempotencyService,
                outboxService,
                jsonCodec,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        service.createRun(
                taskId,
                Map.of("prompt", "测试"),
                List.of(assetId),
                null,
                Map.of(),
                null,
                "run-key"
        );

        InOrder order = inOrder(runRepository, assetService);
        order.verify(runRepository).saveAndFlush(any(TaskRunEntity.class));
        order.verify(assetService).recordRunInputs(
                any(UUID.class),
                eq(List.of(assetId)),
                eq(Map.of("prompt", "测试")),
                eq(now)
        );
    }

    private static final class Fixture {
        private final UUID tenantId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final TaskRepository taskRepository = mock(TaskRepository.class);
        private final TaskRunRepository taskRunRepository = mock(TaskRunRepository.class);
        private final FeatureCatalogService catalogService = mock(FeatureCatalogService.class);
        private final TaskApplicationService service;

        private Fixture() {
            ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
            when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
            service = new TaskApplicationService(
                    taskRepository,
                    taskRunRepository,
                    new TaskPromptSummaryService(),
                    mock(JobRepository.class),
                    catalogService,
                    mock(ProjectService.class),
                    mock(ArtifactService.class),
                    mock(AssetService.class),
                    mock(ModelCatalogService.class),
                    actorContextProvider,
                    mock(IdempotencyService.class),
                    mock(OutboxService.class),
                    mock(JsonCodec.class),
                    Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
            );
        }

        private TaskEntity task(String featureCode) {
            return new TaskEntity(
                    UUID.randomUUID(),
                    tenantId,
                    userId,
                    null,
                    featureCode,
                    "Task title",
                    Instant.parse("2026-07-23T00:00:00Z")
            );
        }

        private TaskRunEntity firstRun(TaskEntity task, String topic) {
            return new TaskRunEntity(
                    UUID.randomUUID(),
                    tenantId,
                    userId,
                    task.getId(),
                    1,
                    task.getFeatureCode(),
                    1,
                    Map.of("topic", topic),
                    List.of(),
                    Instant.parse("2026-07-23T00:00:00Z")
            );
        }
    }
}
