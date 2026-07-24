package com.aibox.platform.task;

import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.catalog.FeatureCatalogService;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(fixture.taskRepository
                .findOwnedByTitleOrPromptKeyword(
                        fixture.tenantId, fixture.userId, "周报"
                ))
                .thenReturn(List.of(task));

        List<TaskApplicationService.TaskView> tasks =
                fixture.service.listTasks(null, " 周报 ");

        assertThat(tasks).extracting(TaskApplicationService.TaskView::title)
                .containsExactly("Task title");
        verify(fixture.taskRepository)
                .findOwnedByTitleOrPromptKeyword(
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
