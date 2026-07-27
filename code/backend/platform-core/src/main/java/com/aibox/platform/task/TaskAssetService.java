package com.aibox.platform.task;

import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskAssetService {

    public static final String DOCUMENT_SOURCE_ROLE = "DOCUMENT_SOURCE";
    private static final int MAX_DOCUMENTS = 10;
    private static final long MAX_DOCUMENT_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_DOCUMENT_BYTES = 200L * 1024 * 1024;
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".md", ".csv", ".json"
    );

    private final TaskRepository taskRepository;
    private final AssetService assetService;
    private final ActorContextProvider actorContextProvider;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public TaskAssetService(
            TaskRepository taskRepository,
            AssetService assetService,
            ActorContextProvider actorContextProvider,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.assetService = assetService;
        this.actorContextProvider = actorContextProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskAssetView> list(UUID taskId) {
        TaskEntity task = requireOwnedTask(taskId);
        List<RelationRow> rows = jdbcTemplate.query("""
                select task_id, asset_id, role, status, ordinal,
                       added_at, removed_at
                from task_asset
                where task_id = ?
                order by role, ordinal
                """, (resultSet, rowNumber) -> new RelationRow(
                resultSet.getObject("task_id", UUID.class),
                resultSet.getObject("asset_id", UUID.class),
                resultSet.getString("role"),
                resultSet.getString("status"),
                resultSet.getInt("ordinal"),
                resultSet.getTimestamp("added_at").toInstant(),
                resultSet.getTimestamp("removed_at") == null
                        ? null : resultSet.getTimestamp("removed_at").toInstant()
        ), task.getId());
        if (rows.isEmpty()) return List.of();
        List<AssetService.AssetView> assets = assetService.describeIncludingDeleted(
                rows.stream().map(RelationRow::assetId).toList()
        );
        List<TaskAssetView> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            RelationRow row = rows.get(index);
            result.add(new TaskAssetView(
                    row.role(), row.status(), row.ordinal(), assets.get(index),
                    row.addedAt(), row.removedAt()
            ));
        }
        return List.copyOf(result);
    }

    @Transactional
    public List<TaskAssetView> add(UUID taskId, List<UUID> assetIds, String role) {
        TaskEntity task = requireOwnedTaskForUpdate(taskId);
        String normalizedRole = normalizeRole(role);
        List<UUID> ids = normalizeIds(assetIds);
        if (ids.isEmpty()) {
            throw new PlatformException("TASK_ASSET_SELECTION_EMPTY", "Select at least one document");
        }
        assetService.requireOwnedAll(ids);
        List<AssetService.AssetView> assets = ids.stream().map(assetService::get).toList();
        List<TaskAssetView> current = list(taskId).stream()
                .filter(item -> normalizedRole.equals(item.role()))
                .filter(item -> "ACTIVE".equals(item.status()))
                .toList();
        LinkedHashSet<UUID> combinedIds = new LinkedHashSet<>();
        current.forEach(item -> combinedIds.add(item.asset().id()));
        combinedIds.addAll(ids);
        if (combinedIds.size() > MAX_DOCUMENTS) {
            throw new PlatformException(
                    "TASK_DOCUMENT_LIMIT_EXCEEDED", "A document session accepts at most 10 files"
            );
        }
        Map<UUID, AssetService.AssetView> assetsById = new java.util.LinkedHashMap<>();
        current.forEach(item -> assetsById.put(item.asset().id(), item.asset()));
        assets.forEach(asset -> assetsById.put(asset.id(), asset));
        long totalBytes = 0;
        for (AssetService.AssetView asset : assetsById.values()) {
            if (!asset.available()) {
                throw new PlatformException(
                        "TASK_DOCUMENT_UNAVAILABLE", "A selected document is no longer available"
                );
            }
            if (!"DOCUMENT".equals(asset.category())
                    || !DOCUMENT_EXTENSIONS.contains(extension(asset.name()))) {
                throw new PlatformException(
                        "TASK_DOCUMENT_TYPE_UNSUPPORTED", "This document type is not supported"
                );
            }
            if (asset.sizeBytes() > MAX_DOCUMENT_BYTES) {
                throw new PlatformException(
                        "TASK_DOCUMENT_TOO_LARGE", "A document cannot exceed 50 MB"
                );
            }
            totalBytes += asset.sizeBytes();
        }
        if (totalBytes > MAX_TOTAL_DOCUMENT_BYTES) {
            throw new PlatformException(
                    "TASK_DOCUMENT_TOTAL_TOO_LARGE", "Session documents cannot exceed 200 MB"
            );
        }
        Integer maximum = jdbcTemplate.queryForObject("""
                select coalesce(max(ordinal), -1)
                from task_asset
                where task_id = ? and role = ?
                """, Integer.class, task.getId(), normalizedRole);
        int ordinal = maximum == null ? 0 : maximum + 1;
        Instant now = clock.instant();
        for (int index = 0; index < ids.size(); index++) {
            AssetService.AssetView asset = assets.get(index);
            jdbcTemplate.update("""
                    insert into task_asset (
                        task_id, asset_id, role, status, ordinal,
                        snapshot_name, snapshot_media_type, snapshot_size_bytes,
                        added_at, removed_at
                    ) values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, null)
                    on conflict (task_id, asset_id, role) do update
                    set status = 'ACTIVE',
                        ordinal = excluded.ordinal,
                        snapshot_name = excluded.snapshot_name,
                        snapshot_media_type = excluded.snapshot_media_type,
                        snapshot_size_bytes = excluded.snapshot_size_bytes,
                        added_at = excluded.added_at,
                        removed_at = null
                    """,
                    task.getId(), ids.get(index), normalizedRole, ordinal + index,
                    asset.name(), asset.mediaType(), asset.sizeBytes(), Timestamp.from(now)
            );
        }
        task.touch(now);
        taskRepository.save(task);
        return list(taskId);
    }

    @Transactional
    public List<TaskAssetView> remove(UUID taskId, UUID assetId, String role) {
        TaskEntity task = requireOwnedTaskForUpdate(taskId);
        int updated = jdbcTemplate.update("""
                update task_asset
                set status = 'REMOVED', removed_at = ?
                where task_id = ? and asset_id = ? and role = ? and status = 'ACTIVE'
                """, Timestamp.from(clock.instant()), task.getId(), assetId, normalizeRole(role));
        if (updated == 0) {
            throw new NotFoundException("task asset", assetId);
        }
        task.touch(clock.instant());
        taskRepository.save(task);
        return list(taskId);
    }

    @Transactional(readOnly = true)
    public List<UUID> activeAssetIds(UUID taskId, String role) {
        TaskEntity task = requireOwnedTask(taskId);
        return jdbcTemplate.query("""
                select asset_id
                from task_asset
                where task_id = ? and role = ? and status = 'ACTIVE'
                order by ordinal
                """, (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                task.getId(), normalizeRole(role));
    }

    private TaskEntity requireOwnedTask(UUID taskId) {
        ActorContext actor = actorContextProvider.current();
        return taskRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        taskId, actor.tenantId(), actor.userId()
                )
                .orElseThrow(() -> new NotFoundException("task", taskId));
    }

    private TaskEntity requireOwnedTaskForUpdate(UUID taskId) {
        ActorContext actor = actorContextProvider.current();
        return taskRepository.findOwnedForUpdate(taskId, actor.tenantId(), actor.userId())
                .orElseThrow(() -> new NotFoundException("task", taskId));
    }

    private static List<UUID> normalizeIds(List<UUID> values) {
        return values == null ? List.of() : values.stream().distinct().toList();
    }

    private static String normalizeRole(String value) {
        String normalized = value == null || value.isBlank()
                ? DOCUMENT_SOURCE_ROLE : value.trim().toUpperCase(Locale.ROOT);
        if (!DOCUMENT_SOURCE_ROLE.equals(normalized)) {
            throw new PlatformException("TASK_ASSET_ROLE_INVALID", "Unsupported task asset role");
        }
        return normalized;
    }

    private static String extension(String name) {
        int index = name == null ? -1 : name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    public record TaskAssetView(
            String role,
            String status,
            int ordinal,
            AssetService.AssetView asset,
            Instant addedAt,
            Instant removedAt
    ) {
    }

    private record RelationRow(
            UUID taskId,
            UUID assetId,
            String role,
            String status,
            int ordinal,
            Instant addedAt,
            Instant removedAt
    ) {
    }
}
