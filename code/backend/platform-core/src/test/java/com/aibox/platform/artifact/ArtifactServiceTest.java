package com.aibox.platform.artifact;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.task.TaskEntity;
import com.aibox.platform.task.TaskRepository;
import com.aibox.platform.task.TaskRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactServiceTest {

    @Test
    void storesGeneratedAssetsAndBuildsAChildVersion() {
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        AssetService assetService = mock(AssetService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID outputAssetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-14T00:00:00Z");

        ArtifactEntity parent = new ArtifactEntity(
                parentId, tenantId, userId, taskId, UUID.randomUUID(), null, 2,
                "image", "previous", "image/png", Map.of("assetId", UUID.randomUUID().toString()), Map.of(), now
        );
        TaskRunEntity run = new TaskRunEntity(
                runId, tenantId, userId, taskId, 3, "image.generate", 1,
                Map.of(), List.of(), parentId, now
        );
        when(artifactRepository.findByIdAndTenantIdAndUserId(parentId, tenantId, userId))
                .thenReturn(Optional.of(parent));
        when(artifactRepository.findMaxVersionNumber(taskId)).thenReturn(2);
        when(artifactRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetService.storeGenerated(any(), any(), anyString(), anyString(), any()))
                .thenReturn(new AssetService.AssetView(
                        outputAssetId, "result.png", "image/png", 3, "sha", now
                ));
        when(taskRepository.findOwnedForUpdate(taskId, tenantId, userId))
                .thenReturn(Optional.of(mock(TaskEntity.class)));

        ArtifactService service = new ArtifactService(
                artifactRepository,
                taskRepository,
                assetService,
                jdbcTemplate,
                mock(ActorContextProvider.class),
                Clock.fixed(now, ZoneOffset.UTC)
        );
        ArtifactDraft draft = ArtifactDrafts.generatedImage(
                "result", "result.png", "image/png", new byte[]{1, 2, 3}, Map.of()
        );

        ArtifactEntity saved = service.saveRunArtifacts(run, List.of(draft)).get(0);

        assertThat(saved.getParentArtifactId()).isEqualTo(parentId);
        assertThat(saved.getVersionNumber()).isEqualTo(3);
        assertThat(saved.getContent()).containsEntry("assetId", outputAssetId.toString());
        verify(jdbcTemplate).update(
                anyString(), any(UUID.class), any(UUID.class), anyString(), any(java.sql.Timestamp.class)
        );
    }
}
