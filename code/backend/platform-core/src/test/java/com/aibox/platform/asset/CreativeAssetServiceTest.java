package com.aibox.platform.asset;

import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.project.ProjectService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreativeAssetServiceTest {

    @Test
    void creatingTheSameActiveAssetReusesTheExistingRecord() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID creativeAssetId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-07T08:00:00Z");
        CreativeAssetEntity existing = new CreativeAssetEntity(
                creativeAssetId,
                tenantId,
                userId,
                null,
                CreativeAssetScope.GLOBAL,
                CreativeAssetType.CHARACTER,
                "角色甲",
                "黑色短发，深色外套",
                "沉着冷静",
                now
        );
        CreativeAssetRepository repository = mock(CreativeAssetRepository.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(repository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                tenantId, userId
        )).thenReturn(java.util.List.of(existing));
        CreativeAssetService service = new CreativeAssetService(
                repository,
                mock(AssetService.class),
                mock(ProjectService.class),
                actorContextProvider,
                Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        );

        CreativeAssetService.CreativeAssetView result = service.create(
                new CreativeAssetService.CreateCreativeAsset(
                        null,
                        "GLOBAL",
                        "CHARACTER",
                        " 角色甲 ",
                        " 黑色短发，深色外套 ",
                        " 沉着冷静 "
                )
        );

        assertThat(result.id()).isEqualTo(creativeAssetId);
        verify(repository, never()).save(any(CreativeAssetEntity.class));
    }

    @Test
    void regeneratingACharacterPrimaryImageClearsTheCurrentThreeView() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID creativeAssetId = UUID.randomUUID();
        UUID oldPrimaryAssetId = UUID.randomUUID();
        UUID oldThreeViewAssetId = UUID.randomUUID();
        UUID newPrimaryAssetId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-07T09:00:00Z");
        Instant updatedAt = Instant.parse("2026-08-07T10:00:00Z");
        CreativeAssetEntity entity = new CreativeAssetEntity(
                creativeAssetId,
                tenantId,
                userId,
                null,
                CreativeAssetScope.GLOBAL,
                CreativeAssetType.CHARACTER,
                "角色甲",
                "黑色短发，深色外套",
                "沉着冷静",
                createdAt
        );
        entity.updateAssets(oldPrimaryAssetId, oldThreeViewAssetId, null, null, createdAt);

        CreativeAssetRepository repository = mock(CreativeAssetRepository.class);
        AssetService assetService = mock(AssetService.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(repository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                creativeAssetId, tenantId, userId
        )).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(assetService.get(newPrimaryAssetId)).thenReturn(new AssetService.AssetView(
                newPrimaryAssetId,
                "character-primary.png",
                "image/png",
                1024,
                "sha256",
                updatedAt,
                "GENERATED",
                "IMAGE",
                "READY",
                true,
                0,
                null
        ));
        CreativeAssetService service = new CreativeAssetService(
                repository,
                assetService,
                mock(ProjectService.class),
                actorContextProvider,
                Clock.fixed(updatedAt, ZoneOffset.UTC)
        );

        CreativeAssetService.CreativeAssetView result = service.update(
                creativeAssetId,
                new CreativeAssetService.UpdateCreativeAsset(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        newPrimaryAssetId,
                        null,
                        null,
                        null,
                        true
                )
        );

        assertThat(result.currentPrimaryAssetId()).isEqualTo(newPrimaryAssetId);
        assertThat(result.currentThreeViewAssetId()).isNull();
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
    }
}
