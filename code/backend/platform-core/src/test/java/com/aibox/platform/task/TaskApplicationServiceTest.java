package com.aibox.platform.task;

import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.catalog.FeatureCatalogService;
import com.aibox.platform.catalog.FeatureDefinitionEntity;
import com.aibox.platform.common.JsonCodec;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskApplicationServiceTest {

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
}
