package com.aibox.platform.asset;

import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void softDeleteKeepsMetadataAndRemovesTheLastPhysicalBlob() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID blobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        String storageKey = tenantId + "/" + userId + "/blobs/" + blobId;
        Path storedFile = storageRoot.resolve(storageKey);
        Files.createDirectories(storedFile.getParent());
        Files.write(storedFile, new byte[]{1, 2, 3});

        AssetEntity asset = new AssetEntity(
                assetId,
                tenantId,
                userId,
                "result.png",
                "image/png",
                3,
                storageKey,
                blobId,
                "sha256",
                AssetOrigin.MODEL_OUTPUT,
                AssetMediaCategory.IMAGE,
                "READY",
                now.minusSeconds(60)
        );
        AssetBlobEntity blob = new AssetBlobEntity(
                blobId,
                tenantId,
                userId,
                "sha256",
                3,
                storageKey,
                now.minusSeconds(60)
        );
        AssetRepository repository = mock(AssetRepository.class);
        AssetBlobRepository blobRepository = mock(AssetBlobRepository.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(repository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                assetId, tenantId, userId
        )).thenReturn(Optional.of(asset));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(assetId)))
                .thenReturn(0);
        when(repository.countByBlobIdAndDeletedAtIsNull(blobId)).thenReturn(0L);
        when(blobRepository.findById(blobId)).thenReturn(Optional.of(blob));

        AssetService service = new AssetService(
                repository,
                blobRepository,
                actorContextProvider,
                jdbcTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                storageRoot.toString(),
                20 * 1024 * 1024,
                100 * 1024 * 1024,
                200 * 1024 * 1024,
                1024L * 1024 * 1024
        );

        service.deleteOwned(assetId);

        assertThat(asset.getStatus()).isEqualTo("DELETED");
        assertThat(asset.getDeletedAt()).isEqualTo(now);
        assertThat(blob.getStatus()).isEqualTo("DELETED");
        assertThat(Files.exists(storedFile)).isFalse();
        verify(repository).saveAndFlush(asset);
        verify(blobRepository).save(blob);
    }
}
