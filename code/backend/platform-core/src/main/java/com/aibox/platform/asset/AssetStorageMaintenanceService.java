package com.aibox.platform.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(
        name = "yuanzuo.asset.orphan-cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public final class AssetStorageMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(AssetStorageMaintenanceService.class);
    private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Path storageRoot;

    public AssetStorageMaintenanceService(
            JdbcTemplate jdbcTemplate,
            Clock clock,
            @Value("${yuanzuo.asset.storage-path}") String storagePath
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @Scheduled(
            initialDelayString = "${yuanzuo.asset.orphan-cleanup-initial-delay-ms:120000}",
            fixedDelayString = "${yuanzuo.asset.orphan-cleanup-interval-ms:86400000}"
    )
    public void scheduledCleanup() {
        CleanupReport report = cleanupNow();
        if (report.deletedFileCount() > 0) {
            log.info(
                    "Removed {} orphaned asset files ({} bytes)",
                    report.deletedFileCount(),
                    report.deletedBytes()
            );
        }
    }

    CleanupReport cleanupNow() {
        if (!Files.isDirectory(storageRoot)) return new CleanupReport(0, 0);
        Set<String> retainedKeys = new HashSet<>(jdbcTemplate.queryForList("""
                select storage_key
                from asset
                where deleted_at is null
                union
                select storage_key
                from asset_blob
                where status = 'READY'
                """, String.class));
        Instant cutoff = clock.instant().minus(ORPHAN_GRACE_PERIOD);
        int deletedFileCount = 0;
        long deletedBytes = 0;
        try (Stream<Path> paths = Files.walk(storageRoot)) {
            List<Path> candidates = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isManagedStorageKey(storageKey(path)))
                    .filter(path -> !retainedKeys.contains(storageKey(path)))
                    .toList();
            for (Path path : candidates) {
                try {
                    FileTime modifiedAt = Files.getLastModifiedTime(path);
                    if (!modifiedAt.toInstant().isBefore(cutoff)) continue;
                    long size = Files.size(path);
                    if (Files.deleteIfExists(path)) {
                        deletedFileCount++;
                        deletedBytes += size;
                    }
                } catch (IOException exception) {
                    log.warn("Could not remove an orphaned asset file");
                }
            }
        } catch (IOException exception) {
            log.warn("Could not scan asset storage for orphaned files");
        }
        return new CleanupReport(deletedFileCount, deletedBytes);
    }

    private String storageKey(Path path) {
        return storageRoot.relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static boolean isManagedStorageKey(String storageKey) {
        String[] segments = storageKey.split("/");
        if (segments.length < 3 || !isUuid(segments[0]) || !isUuid(segments[1])) {
            return false;
        }
        if (segments.length == 3) {
            return isUuid(segments[2]) || isUploadingName(segments[2]);
        }
        if (segments.length != 4) return false;
        if ("blobs".equals(segments[2])) return isUuid(segments[3]);
        return ".uploads".equals(segments[2]) && isUploadingName(segments[3]);
    }

    private static boolean isUploadingName(String value) {
        if (!value.endsWith(".uploading")) return false;
        return isUuid(value.substring(0, value.length() - ".uploading".length()));
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    record CleanupReport(int deletedFileCount, long deletedBytes) {
    }
}
