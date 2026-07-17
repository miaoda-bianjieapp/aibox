package com.aibox.platform.asset;

import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.ConflictException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AssetService {

    private final AssetRepository repository;
    private final ActorContextProvider actorContextProvider;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Path storageRoot;
    private final long maxSizeBytes;

    public AssetService(
            AssetRepository repository,
            ActorContextProvider actorContextProvider,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${yuanzuo.asset.storage-path}") String storagePath,
            @Value("${yuanzuo.asset.max-size-bytes:52428800}") long maxSizeBytes
    ) {
        this.repository = repository;
        this.actorContextProvider = actorContextProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
    }

    @Transactional
    public AssetView upload(String originalName, String contentType, long size, InputStream inputStream) {
        ActorContext actor = actorContextProvider.current();
        return store(actor.tenantId(), actor.userId(), originalName, contentType, size, inputStream);
    }

    @Transactional
    public AssetView storeGenerated(
            UUID tenantId,
            UUID userId,
            String originalName,
            String contentType,
            byte[] content
    ) {
        byte[] bytes = content == null ? new byte[0] : content.clone();
        return store(tenantId, userId, originalName, contentType, bytes.length, new ByteArrayInputStream(bytes));
    }

    private AssetView store(
            UUID tenantId,
            UUID userId,
            String originalName,
            String contentType,
            long size,
            InputStream inputStream
    ) {
        if (inputStream == null || size <= 0) {
            throw new PlatformException("ASSET_EMPTY", "Uploaded file is empty");
        }
        if (size > maxSizeBytes) {
            throw new PlatformException("ASSET_TOO_LARGE", "Uploaded file exceeds the configured size limit");
        }

        UUID id = UUID.randomUUID();
        String storageKey = tenantId + "/" + userId + "/" + id;
        Path target = resolveStorageKey(storageKey);
        Path temporary = target.resolveSibling(id + ".uploading");
        String sha256;
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new DigestInputStream(inputStream, digest)) {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            tryDelete(temporary);
            throw new PlatformException("ASSET_STORAGE_FAILED", "Uploaded file could not be stored");
        }

        AssetEntity asset = new AssetEntity(
                id,
                tenantId,
                userId,
                normalizeName(originalName),
                normalizeMediaType(contentType),
                size,
                storageKey,
                sha256,
                clock.instant()
        );
        AssetView view = toView(repository.save(asset));
        deleteFileAfterRollback(target);
        return view;
    }

    @Transactional(readOnly = true)
    public List<AssetView> list() {
        ActorContext actor = actorContextProvider.current();
        return repository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        actor.tenantId(), actor.userId())
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetDownload download(UUID id) {
        AssetEntity asset = requireOwned(id);
        Path path = resolveStorageKey(asset.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("asset content", id);
        }
        return new AssetDownload(toView(asset), new FileSystemResource(path));
    }

    @Transactional(readOnly = true)
    public ModelAsset readForModel(UUID id) {
        AssetEntity asset = requireOwned(id);
        try {
            return new ModelAsset(
                    asset.getId(),
                    asset.getOriginalName(),
                    asset.getMediaType(),
                    Files.readAllBytes(resolveStorageKey(asset.getStorageKey()))
            );
        } catch (IOException exception) {
            throw new PlatformException("ASSET_READ_FAILED", "Asset content could not be read");
        }
    }

    @Transactional(readOnly = true)
    public void requireOwnedAll(List<UUID> ids) {
        if (ids == null) return;
        ids.forEach(this::requireOwned);
    }

    @Transactional(readOnly = true)
    public List<InputAssetReference> describeOwnedAll(List<UUID> ids) {
        if (ids == null) return List.of();
        return ids.stream()
                .map(this::requireOwned)
                .map(this::describe)
                .toList();
    }

    @Transactional
    public void delete(UUID id) {
        AssetEntity asset = requireOwned(id);
        Integer references = jdbcTemplate.queryForObject(
                "select count(*) from task_run where tenant_id = ? and user_id = ? "
                        + "and jsonb_exists(input_asset_ids_json, ?)",
                Integer.class,
                asset.getTenantId(),
                asset.getUserId(),
                id.toString()
        );
        if (references != null && references > 0) {
            throw new ConflictException("ASSET_IN_USE", "Asset is referenced by task history and cannot be deleted");
        }
        Integer artifactReferences = jdbcTemplate.queryForObject(
                "select count(*) from artifact_asset where asset_id = ?",
                Integer.class,
                id
        );
        if (artifactReferences != null && artifactReferences > 0) {
            throw new ConflictException("ASSET_IN_USE", "Asset is referenced by an artifact and cannot be deleted");
        }
        asset.delete(clock.instant());
        tryDelete(resolveStorageKey(asset.getStorageKey()));
    }

    public long countOwned() {
        ActorContext actor = actorContextProvider.current();
        return repository.countByTenantIdAndUserIdAndDeletedAtIsNull(actor.tenantId(), actor.userId());
    }

    public long totalOwnedBytes() {
        ActorContext actor = actorContextProvider.current();
        return repository.sumSizeByOwner(actor.tenantId(), actor.userId());
    }

    private AssetEntity requireOwned(UUID id) {
        ActorContext actor = actorContextProvider.current();
        return repository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("asset", id));
    }

    private Path resolveStorageKey(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new PlatformException("INVALID_STORAGE_KEY", "Asset storage key is invalid");
        }
        return resolved;
    }

    private AssetView toView(AssetEntity asset) {
        return new AssetView(
                asset.getId(), asset.getOriginalName(), asset.getMediaType(), asset.getSizeBytes(),
                asset.getSha256(), asset.getCreatedAt()
        );
    }

    private InputAssetReference describe(AssetEntity asset) {
        ImageDimensions dimensions = imageDimensions(asset);
        return new InputAssetReference(
                asset.getId(),
                asset.getOriginalName(),
                asset.getMediaType(),
                asset.getSizeBytes(),
                dimensions == null ? null : dimensions.width(),
                dimensions == null ? null : dimensions.height()
        );
    }

    private ImageDimensions imageDimensions(AssetEntity asset) {
        if (asset.getMediaType() == null
                || !asset.getMediaType().toLowerCase(Locale.ROOT).startsWith("image/")) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(
                resolveStorageKey(asset.getStorageKey()).toFile()
        )) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new ImageDimensions(width, height) : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) return "unnamed-file";
        String clean = value.replace('\\', '/');
        String normalized = clean.substring(clean.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank()) normalized = "unnamed-file";
        return normalized.length() <= 500 ? normalized : normalized.substring(normalized.length() - 500);
    }

    private static String normalizeMediaType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value;
    }

    private static void tryDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void deleteFileAfterRollback(Path path) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    tryDelete(path);
                }
            }
        });
    }

    public record AssetView(UUID id, String name, String mediaType, long sizeBytes, String sha256,
                            java.time.Instant createdAt) {
    }

    public record AssetDownload(AssetView asset, Resource resource) {
    }

    private record ImageDimensions(int width, int height) {
    }
}
