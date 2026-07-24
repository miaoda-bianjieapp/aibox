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
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AssetService {

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".txt", ".md", ".markdown", ".json", ".csv", ".pdf",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"
    );
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".aac", ".m4a", ".wav", ".flac", ".ogg"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mov", ".m4v", ".webm"
    );
    private static final Duration DERIVED_ASSET_MAX_AGE = Duration.ofHours(24);

    private final AssetRepository repository;
    private final AssetBlobRepository blobRepository;
    private final ActorContextProvider actorContextProvider;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Path storageRoot;
    private final long maxImageSizeBytes;
    private final long maxDocumentSizeBytes;
    private final long maxAudioSizeBytes;
    private final long maxVideoSizeBytes;

    public AssetService(
            AssetRepository repository,
            AssetBlobRepository blobRepository,
            ActorContextProvider actorContextProvider,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${yuanzuo.asset.storage-path}") String storagePath,
            @Value("${yuanzuo.asset.max-image-size-bytes:20971520}") long maxImageSizeBytes,
            @Value("${yuanzuo.asset.max-document-size-bytes:104857600}") long maxDocumentSizeBytes,
            @Value("${yuanzuo.asset.max-audio-size-bytes:209715200}") long maxAudioSizeBytes,
            @Value("${yuanzuo.asset.max-video-size-bytes:1073741824}") long maxVideoSizeBytes
    ) {
        this.repository = repository;
        this.blobRepository = blobRepository;
        this.actorContextProvider = actorContextProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxImageSizeBytes = maxImageSizeBytes;
        this.maxDocumentSizeBytes = maxDocumentSizeBytes;
        this.maxAudioSizeBytes = maxAudioSizeBytes;
        this.maxVideoSizeBytes = maxVideoSizeBytes;
    }

    @Transactional
    public AssetView upload(String originalName, String contentType, long size, InputStream inputStream) {
        return upload(originalName, contentType, size, inputStream, AssetOrigin.USER_UPLOAD);
    }

    @Transactional
    public AssetView upload(
            String originalName,
            String contentType,
            long size,
            InputStream inputStream,
            AssetOrigin origin
    ) {
        ActorContext actor = actorContextProvider.current();
        AssetOrigin normalizedOrigin = origin == null ? AssetOrigin.USER_UPLOAD : origin;
        if (normalizedOrigin == AssetOrigin.MODEL_OUTPUT) {
            throw new PlatformException("ASSET_ORIGIN_INVALID", "Model outputs can only be created by the platform");
        }
        return store(
                actor.tenantId(), actor.userId(), originalName, contentType,
                size, inputStream, normalizedOrigin
        );
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
        return store(
                tenantId, userId, originalName, contentType, bytes.length,
                new ByteArrayInputStream(bytes), AssetOrigin.MODEL_OUTPUT
        );
    }

    private AssetView store(
            UUID tenantId,
            UUID userId,
            String originalName,
            String contentType,
            long size,
            InputStream inputStream,
            AssetOrigin origin
    ) {
        if (inputStream == null || size <= 0) {
            throw new PlatformException("ASSET_EMPTY", "Uploaded file is empty");
        }

        UUID id = UUID.randomUUID();
        String name = normalizeName(originalName);
        String mediaType = normalizeMediaType(contentType);
        AssetMediaCategory category = mediaCategory(name, mediaType);
        validateAllowedType(name, category);
        validateSize(category, size);
        cleanupStaleDerivedForOwner(
                tenantId,
                userId,
                clock.instant().minus(DERIVED_ASSET_MAX_AGE)
        );

        Path temporary = resolveStorageKey(tenantId + "/" + userId + "/.uploads/" + id + ".uploading");
        String sha256;
        long actualSize;
        try {
            Files.createDirectories(temporary.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = new DigestInputStream(inputStream, digest)) {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
            actualSize = Files.size(temporary);
        } catch (IOException | NoSuchAlgorithmException exception) {
            tryDelete(temporary);
            throw new PlatformException("ASSET_STORAGE_FAILED", "Uploaded file could not be stored");
        }
        if (actualSize <= 0) {
            tryDelete(temporary);
            throw new PlatformException("ASSET_EMPTY", "Uploaded file is empty");
        }
        if (actualSize != size) {
            tryDelete(temporary);
            throw new PlatformException("ASSET_SIZE_MISMATCH", "Uploaded file size does not match its metadata");
        }

        Instant now = clock.instant();
        AssetBlobEntity blob = blobRepository
                .findByTenantIdAndUserIdAndSha256AndSizeBytesAndStatus(
                        tenantId, userId, sha256, actualSize, "READY"
                )
                .orElse(null);
        if (blob == null) {
            UUID blobId = UUID.randomUUID();
            String storageKey = tenantId + "/" + userId + "/blobs/" + blobId;
            Path target = resolveStorageKey(storageKey);
            try {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                tryDelete(temporary);
                throw new PlatformException("ASSET_STORAGE_FAILED", "Uploaded file could not be stored");
            }
            blob = blobRepository.save(new AssetBlobEntity(
                    blobId, tenantId, userId, sha256, actualSize, storageKey, now
            ));
            deleteFileAfterRollback(target);
        } else {
            tryDelete(temporary);
        }

        AssetEntity asset = new AssetEntity(
                id,
                tenantId,
                userId,
                name,
                mediaType,
                actualSize,
                blob.getStorageKey(),
                blob.getId(),
                sha256,
                origin,
                category,
                origin == AssetOrigin.APP_DERIVED ? "TEMPORARY" : "READY",
                now
        );
        AssetView view = toView(repository.save(asset));
        return view;
    }

    @Transactional(readOnly = true)
    public List<AssetView> list() {
        ActorContext actor = actorContextProvider.current();
        return repository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        actor.tenantId(), actor.userId())
                .stream()
                .filter(asset -> asset.getOrigin() != AssetOrigin.APP_DERIVED)
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetView get(UUID id) {
        return toView(requireOwnedIncludingDeleted(id));
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
    public AssetStoredFile openForPreview(UUID id) {
        AssetEntity asset = requireOwned(id);
        Path path = resolveStorageKey(asset.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new NotFoundException("asset content", id);
        }
        return new AssetStoredFile(toView(asset), path);
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

    @Transactional(readOnly = true)
    public List<AssetView> describeIncludingDeleted(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(this::requireOwnedIncludingDeleted)
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void recordRunInputs(
            UUID runId,
            List<UUID> assetIds,
            Map<String, Object> parameters,
            Instant createdAt
    ) {
        if (assetIds == null || assetIds.isEmpty()) return;
        Map<UUID, String> fields = inputFields(assetIds, parameters);
        for (int index = 0; index < assetIds.size(); index++) {
            UUID assetId = assetIds.get(index);
            AssetEntity asset = requireOwned(assetId);
            jdbcTemplate.update("""
                    insert into task_run_asset (
                        run_id, asset_id, direction, field_key, ordinal,
                        snapshot_name, snapshot_media_type, snapshot_size_bytes, created_at
                    ) values (?, ?, 'INPUT', ?, ?, ?, ?, ?, ?)
                    on conflict (run_id, direction, field_key, ordinal) do nothing
                    """,
                    runId,
                    assetId,
                    fields.getOrDefault(assetId, "attachment"),
                    index,
                    asset.getOriginalName(),
                    asset.getMediaType(),
                    asset.getSizeBytes(),
                    Timestamp.from(createdAt)
            );
        }
    }

    @Transactional(readOnly = true)
    public List<AssetView> listRunInputs(UUID runId) {
        List<UUID> ids = jdbcTemplate.query("""
                select relation.asset_id
                from task_run_asset relation
                join asset on asset.id = relation.asset_id
                where relation.run_id = ?
                  and relation.direction = 'INPUT'
                  and asset.origin <> 'APP_DERIVED'
                order by relation.ordinal
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), runId);
        return describeIncludingDeleted(ids);
    }

    @Transactional
    public void delete(UUID id) {
        deleteOwned(id);
    }

    @Transactional
    public void deleteOwned(UUID id) {
        AssetEntity asset = requireOwned(id);
        Integer activeReferences = jdbcTemplate.queryForObject("""
                select count(distinct run.id)
                from task_run_asset relation
                join task_run run on run.id = relation.run_id
                where relation.asset_id = ?
                  and run.status in ('CREATED', 'VALIDATING', 'QUEUED', 'RUNNING', 'WAITING_CALLBACK')
                """, Integer.class, id);
        if (activeReferences != null && activeReferences > 0) {
            throw new ConflictException(
                    "ASSET_ACTIVE_RUN",
                    "The file is being used by an active task and cannot be deleted yet"
            );
        }
        softDelete(asset);
    }

    @Transactional
    public void cleanupDerivedForRun(UUID runId) {
        List<UUID> ids = jdbcTemplate.query("""
                select asset.id
                from task_run_asset relation
                join asset on asset.id = relation.asset_id
                where relation.run_id = ?
                  and asset.origin = 'APP_DERIVED'
                  and asset.deleted_at is null
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), runId);
        ids.forEach(this::softDeleteById);
    }

    public long countOwned() {
        ActorContext actor = actorContextProvider.current();
        Long value = jdbcTemplate.queryForObject("""
                select count(*)
                from asset
                where tenant_id = ? and user_id = ?
                  and deleted_at is null and origin <> 'APP_DERIVED'
                """, Long.class, actor.tenantId(), actor.userId());
        return value == null ? 0 : value;
    }

    public long totalOwnedBytes() {
        ActorContext actor = actorContextProvider.current();
        Long value = jdbcTemplate.queryForObject("""
                select coalesce(sum(blob.size_bytes), 0)
                from asset_blob blob
                where blob.tenant_id = ?
                  and blob.user_id = ?
                  and blob.status = 'READY'
                  and exists (
                    select 1
                    from asset
                    where asset.blob_id = blob.id
                      and asset.deleted_at is null
                      and asset.origin <> 'APP_DERIVED'
                  )
                """, Long.class, actor.tenantId(), actor.userId());
        return value == null ? 0 : value;
    }

    private AssetEntity requireOwned(UUID id) {
        ActorContext actor = actorContextProvider.current();
        return repository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("asset", id));
    }

    private AssetEntity requireOwnedIncludingDeleted(UUID id) {
        ActorContext actor = actorContextProvider.current();
        return repository.findByIdAndTenantIdAndUserId(id, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("asset", id));
    }

    Path resolveStorageKey(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new PlatformException("INVALID_STORAGE_KEY", "Asset storage key is invalid");
        }
        return resolved;
    }

    private AssetView toView(AssetEntity asset) {
        return new AssetView(
                asset.getId(), asset.getOriginalName(), asset.getMediaType(), asset.getSizeBytes(),
                asset.getSha256(), asset.getCreatedAt(), asset.getOrigin().name(),
                asset.getMediaCategory().name(), asset.getStatus(),
                asset.getDeletedAt() == null && !"DELETED".equals(asset.getStatus()),
                0, null
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

    private AssetMediaCategory mediaCategory(String name, String mediaType) {
        String normalizedType = mediaType.toLowerCase(Locale.ROOT);
        if (normalizedType.startsWith("image/")) return AssetMediaCategory.IMAGE;
        if (normalizedType.startsWith("video/")) return AssetMediaCategory.VIDEO;
        if (normalizedType.startsWith("audio/")) return AssetMediaCategory.AUDIO;
        String extension = extension(name);
        if (DOCUMENT_EXTENSIONS.contains(extension)
                || normalizedType.startsWith("text/")
                || normalizedType.equals("application/pdf")
                || normalizedType.contains("word")
                || normalizedType.contains("excel")
                || normalizedType.contains("spreadsheet")
                || normalizedType.contains("powerpoint")
                || normalizedType.contains("presentation")) {
            return AssetMediaCategory.DOCUMENT;
        }
        if (IMAGE_EXTENSIONS.contains(extension)) return AssetMediaCategory.IMAGE;
        if (AUDIO_EXTENSIONS.contains(extension)) return AssetMediaCategory.AUDIO;
        if (VIDEO_EXTENSIONS.contains(extension)) return AssetMediaCategory.VIDEO;
        return AssetMediaCategory.OTHER;
    }

    private void validateAllowedType(String name, AssetMediaCategory category) {
        String extension = extension(name);
        boolean allowed = switch (category) {
            case IMAGE -> IMAGE_EXTENSIONS.contains(extension);
            case VIDEO -> VIDEO_EXTENSIONS.contains(extension);
            case AUDIO -> AUDIO_EXTENSIONS.contains(extension);
            case DOCUMENT -> DOCUMENT_EXTENSIONS.contains(extension);
            case OTHER -> false;
        };
        if (!allowed) {
            throw new PlatformException("ASSET_TYPE_NOT_ALLOWED", "This file type is not supported");
        }
    }

    private void validateSize(AssetMediaCategory category, long size) {
        long limit = switch (category) {
            case IMAGE -> maxImageSizeBytes;
            case DOCUMENT -> maxDocumentSizeBytes;
            case AUDIO -> maxAudioSizeBytes;
            case VIDEO -> maxVideoSizeBytes;
            case OTHER -> 0;
        };
        if (limit <= 0 || size > limit) {
            throw new PlatformException("ASSET_TOO_LARGE", "Uploaded file exceeds the limit for its type");
        }
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static Map<UUID, String> inputFields(
            List<UUID> assetIds,
            Map<String, Object> parameters
    ) {
        Map<UUID, String> result = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((field, value) -> {
                if (value instanceof List<?> values) {
                    values.forEach(item -> captureField(assetIds, result, field, item));
                } else {
                    captureField(assetIds, result, field, value);
                }
            });
        }
        return result;
    }

    private static void captureField(
            List<UUID> assetIds,
            Map<UUID, String> result,
            String field,
            Object value
    ) {
        if (value == null) return;
        try {
            UUID id = UUID.fromString(value.toString());
            if (assetIds.contains(id)) result.putIfAbsent(id, field);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void releaseBlobIfUnused(UUID blobId) {
        if (repository.countByBlobIdAndDeletedAtIsNull(blobId) > 0) return;
        AssetBlobEntity blob = blobRepository.findById(blobId).orElse(null);
        if (blob == null || "DELETED".equals(blob.getStatus())) return;
        blob.delete(clock.instant());
        blobRepository.save(blob);
        deleteFileAfterCommit(resolveStorageKey(blob.getStorageKey()));
    }

    private void cleanupStaleDerivedForOwner(UUID tenantId, UUID userId, Instant cutoff) {
        List<UUID> ids = jdbcTemplate.query("""
                select asset.id
                from asset
                where asset.tenant_id = ?
                  and asset.user_id = ?
                  and asset.origin = 'APP_DERIVED'
                  and asset.deleted_at is null
                  and asset.created_at < ?
                  and not exists (
                    select 1
                    from task_run_asset relation
                    join task_run run on run.id = relation.run_id
                    where relation.asset_id = asset.id
                      and run.status in (
                        'CREATED', 'VALIDATING', 'QUEUED',
                        'RUNNING', 'WAITING_CALLBACK'
                      )
                  )
                """,
                (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                tenantId,
                userId,
                Timestamp.from(cutoff)
        );
        ids.forEach(this::softDeleteById);
    }

    private void softDeleteById(UUID id) {
        repository.findById(id)
                .filter(asset -> asset.getDeletedAt() == null)
                .ifPresent(this::softDelete);
    }

    private void softDelete(AssetEntity asset) {
        asset.delete(clock.instant());
        repository.saveAndFlush(asset);
        releaseBlobIfUnused(asset.getBlobId());
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

    private static void deleteFileAfterCommit(Path path) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            tryDelete(path);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tryDelete(path);
            }
        });
    }

    public record AssetView(
            UUID id,
            String name,
            String mediaType,
            long sizeBytes,
            String sha256,
            java.time.Instant createdAt,
            String origin,
            String category,
            String status,
            boolean available,
            long associatedTaskCount,
            String latestTaskTitle
    ) {
        public AssetView(
                UUID id,
                String name,
                String mediaType,
                long sizeBytes,
                String sha256,
                java.time.Instant createdAt
        ) {
            this(
                    id, name, mediaType, sizeBytes, sha256, createdAt,
                    AssetOrigin.USER_UPLOAD.name(), AssetMediaCategory.OTHER.name(),
                    "READY", true, 0, null
            );
        }
    }

    public record AssetDownload(AssetView asset, Resource resource) {
    }

    public record AssetStoredFile(AssetView asset, Path path) {
    }

    private record ImageDimensions(int width, int height) {
    }
}
