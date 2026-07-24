package com.aibox.platform.asset;

import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void uploadingTheSameUserFileTwiceReusesTheActiveAsset() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        byte[] content = "same upload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AssetRepository repository = mock(AssetRepository.class);
        AssetBlobRepository blobRepository = mock(AssetBlobRepository.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AtomicReference<AssetEntity> savedAsset = new AtomicReference<>();
        AtomicReference<AssetBlobEntity> savedBlob = new AtomicReference<>();

        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                any(Object[].class)
        )).thenReturn(List.of());
        when(blobRepository.findByTenantIdAndUserIdAndSha256AndSizeBytes(
                eq(tenantId), eq(userId), anyString(), eq((long) content.length)
        )).thenAnswer(invocation -> Optional.ofNullable(savedBlob.get()));
        when(blobRepository.save(any(AssetBlobEntity.class))).thenAnswer(invocation -> {
            AssetBlobEntity blob = invocation.getArgument(0);
            savedBlob.set(blob);
            return blob;
        });
        when(repository
                .findFirstByTenantIdAndUserIdAndOriginAndOriginalNameAndMediaTypeAndSha256AndSizeBytesAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq(tenantId),
                        eq(userId),
                        eq(AssetOrigin.USER_UPLOAD),
                        eq("notes.txt"),
                        eq("text/plain"),
                        anyString(),
                        eq((long) content.length)
                )).thenAnswer(invocation -> Optional.ofNullable(savedAsset.get()));
        when(repository.save(any(AssetEntity.class))).thenAnswer(invocation -> {
            AssetEntity asset = invocation.getArgument(0);
            savedAsset.set(asset);
            return asset;
        });

        AssetService service = new AssetService(
                repository,
                blobRepository,
                new InProcessAssetContentLock(),
                actorContextProvider,
                jdbcTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                storageRoot.toString(),
                20 * 1024 * 1024,
                100 * 1024 * 1024,
                200 * 1024 * 1024,
                1024L * 1024 * 1024
        );

        AssetService.AssetView first = service.upload(
                "notes.txt",
                "text/plain",
                content.length,
                new ByteArrayInputStream(content)
        );
        AssetService.AssetView second = service.upload(
                "notes.txt",
                "text/plain",
                content.length,
                new ByteArrayInputStream(content)
        );

        assertThat(second.id()).isEqualTo(first.id());
        verify(repository, times(1)).save(any(AssetEntity.class));
        verify(blobRepository, times(1)).save(any(AssetBlobEntity.class));
    }

    @Test
    void concurrentUploadsOfTheSameUserFileCreateOneAsset() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-24T00:30:00Z");
        byte[] content = "concurrent upload"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AssetRepository repository = mock(AssetRepository.class);
        AssetBlobRepository blobRepository = mock(AssetBlobRepository.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AtomicReference<AssetEntity> savedAsset = new AtomicReference<>();
        AtomicReference<AssetBlobEntity> savedBlob = new AtomicReference<>();

        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                any(Object[].class)
        )).thenReturn(List.of());
        when(blobRepository.findByTenantIdAndUserIdAndSha256AndSizeBytes(
                eq(tenantId), eq(userId), anyString(), eq((long) content.length)
        )).thenAnswer(invocation -> Optional.ofNullable(savedBlob.get()));
        when(blobRepository.save(any(AssetBlobEntity.class))).thenAnswer(invocation -> {
            AssetBlobEntity blob = invocation.getArgument(0);
            savedBlob.set(blob);
            return blob;
        });
        when(repository
                .findFirstByTenantIdAndUserIdAndOriginAndOriginalNameAndMediaTypeAndSha256AndSizeBytesAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq(tenantId),
                        eq(userId),
                        eq(AssetOrigin.USER_UPLOAD),
                        eq("shared.txt"),
                        eq("text/plain"),
                        anyString(),
                        eq((long) content.length)
                )).thenAnswer(invocation -> Optional.ofNullable(savedAsset.get()));
        when(repository.save(any(AssetEntity.class))).thenAnswer(invocation -> {
            AssetEntity asset = invocation.getArgument(0);
            savedAsset.set(asset);
            return asset;
        });

        AssetService service = new AssetService(
                repository,
                blobRepository,
                new InProcessAssetContentLock(),
                actorContextProvider,
                jdbcTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                storageRoot.toString(),
                20 * 1024 * 1024,
                100 * 1024 * 1024,
                200 * 1024 * 1024,
                1024L * 1024 * 1024
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Supplier<AssetService.AssetView> upload = () -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent upload did not start");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return service.upload(
                        "shared.txt",
                        "text/plain",
                        content.length,
                        new ByteArrayInputStream(content)
                );
            };
            Future<AssetService.AssetView> first = executor.submit(upload::get);
            Future<AssetService.AssetView> second = executor.submit(upload::get);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).id());
        } finally {
            executor.shutdownNow();
        }
        verify(repository, times(1)).save(any(AssetEntity.class));
        verify(blobRepository, times(1)).save(any(AssetBlobEntity.class));
    }

    @Test
    void uploadingADeletedFileRestoresItsExistingBlob() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID blobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-24T01:00:00Z");
        byte[] content = "upload after deletion"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String storageKey = tenantId + "/" + userId + "/blobs/" + blobId;
        AssetBlobEntity deletedBlob = new AssetBlobEntity(
                blobId,
                tenantId,
                userId,
                "ignored-by-mock",
                content.length,
                storageKey,
                now.minusSeconds(60)
        );
        deletedBlob.delete(now.minusSeconds(30));

        AssetRepository repository = mock(AssetRepository.class);
        AssetBlobRepository blobRepository = mock(AssetBlobRepository.class);
        ActorContextProvider actorContextProvider = mock(ActorContextProvider.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(actorContextProvider.current()).thenReturn(new ActorContext(tenantId, userId));
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any(),
                any(Object[].class)
        )).thenReturn(List.of());
        when(blobRepository.findByTenantIdAndUserIdAndSha256AndSizeBytes(
                eq(tenantId), eq(userId), anyString(), eq((long) content.length)
        )).thenReturn(Optional.of(deletedBlob));
        when(blobRepository.save(any(AssetBlobEntity.class))).thenAnswer(invocation -> {
            AssetBlobEntity blob = invocation.getArgument(0);
            if (blob != deletedBlob) {
                throw new DataIntegrityViolationException("duplicate blob owner hash");
            }
            return blob;
        });
        when(repository
                .findFirstByTenantIdAndUserIdAndOriginAndOriginalNameAndMediaTypeAndSha256AndSizeBytesAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq(tenantId),
                        eq(userId),
                        eq(AssetOrigin.USER_UPLOAD),
                        eq("restored.txt"),
                        eq("text/plain"),
                        anyString(),
                        eq((long) content.length)
                )).thenReturn(Optional.empty());
        when(repository.save(any(AssetEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AssetService service = new AssetService(
                repository,
                blobRepository,
                new InProcessAssetContentLock(),
                actorContextProvider,
                jdbcTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                storageRoot.toString(),
                20 * 1024 * 1024,
                100 * 1024 * 1024,
                200 * 1024 * 1024,
                1024L * 1024 * 1024
        );

        AssetService.AssetView restored = service.upload(
                "restored.txt",
                "text/plain",
                content.length,
                new ByteArrayInputStream(content)
        );

        assertThat(restored.id()).isNotNull();
        assertThat(deletedBlob.getStatus()).isEqualTo("READY");
        assertThat(Files.readAllBytes(storageRoot.resolve(storageKey))).isEqualTo(content);
        verify(blobRepository).save(deletedBlob);
    }

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
                new InProcessAssetContentLock(),
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

    private static final class InProcessAssetContentLock implements AssetContentLock {

        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public <T> T execute(
                UUID tenantId,
                UUID userId,
                String sha256,
                long sizeBytes,
                Supplier<T> operation
        ) {
            String identity = tenantId + ":" + userId + ":" + sha256 + ":" + sizeBytes;
            ReentrantLock lock = locks.computeIfAbsent(identity, ignored -> new ReentrantLock());
            lock.lock();
            try {
                return operation.get();
            } finally {
                lock.unlock();
                if (!lock.hasQueuedThreads()) locks.remove(identity, lock);
            }
        }
    }
}
