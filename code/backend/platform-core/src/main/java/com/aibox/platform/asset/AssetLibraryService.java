package com.aibox.platform.asset;

import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetLibraryService {

    private static final int MAX_SELECTION_SIZE = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final ActorContextProvider actorContextProvider;
    private final AssetService assetService;

    public AssetLibraryService(
            JdbcTemplate jdbcTemplate,
            ActorContextProvider actorContextProvider,
            AssetService assetService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.actorContextProvider = actorContextProvider;
        this.assetService = assetService;
    }

    @Transactional(readOnly = true)
    public AssetPage list(
            String libraryType,
            String category,
            String query,
            String cursor,
            int requestedPageSize
    ) {
        ActorContext actor = actorContextProvider.current();
        AssetOrigin origin = parseLibraryType(libraryType);
        AssetMediaCategory parsedCategory = parseCategory(category);
        int pageSize = Math.max(10, Math.min(50, requestedPageSize));
        int offset = parseCursor(cursor);

        StringBuilder sql = new StringBuilder("""
                select
                    asset.id,
                    asset.original_name,
                    asset.media_type,
                    asset.size_bytes,
                    asset.sha256,
                    asset.created_at,
                    asset.origin,
                    asset.media_category,
                    asset.status,
                    asset.deleted_at,
                    count(distinct reference.task_id) as associated_task_count,
                    max(reference.task_title) as latest_task_title
                from asset
                left join (
                    select relation.asset_id, task.id as task_id, task.title as task_title
                    from task_run_asset relation
                    join task_run run on run.id = relation.run_id
                    join task on task.id = run.task_id
                    union all
                    select relation.asset_id, task.id as task_id, task.title as task_title
                    from artifact_asset relation
                    join artifact on artifact.id = relation.artifact_id
                    join task on task.id = artifact.task_id
                ) reference on reference.asset_id = asset.id
                where asset.tenant_id = ?
                  and asset.user_id = ?
                  and asset.deleted_at is null
                  and asset.origin = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.tenantId());
        arguments.add(actor.userId());
        arguments.add(origin.name());
        if (parsedCategory != null) {
            sql.append(" and asset.media_category = ?");
            arguments.add(parsedCategory.name());
        }
        String normalizedQuery = query == null ? "" : query.trim();
        if (!normalizedQuery.isEmpty()) {
            if (normalizedQuery.length() > 100) {
                throw new PlatformException("ASSET_QUERY_TOO_LONG", "Search text must not exceed 100 characters");
            }
            sql.append("""
                     and (
                        lower(asset.original_name) like ?
                        or lower(reference.task_title) like ?
                     )
                    """);
            String pattern = "%" + normalizedQuery.toLowerCase(Locale.ROOT) + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        sql.append("""
                 group by asset.id
                 order by asset.created_at desc, asset.id desc
                 limit ? offset ?
                """);
        arguments.add(pageSize + 1);
        arguments.add(offset);

        List<AssetService.AssetView> results = jdbcTemplate.query(
                sql.toString(),
                this::mapAsset,
                arguments.toArray()
        );
        boolean hasMore = results.size() > pageSize;
        List<AssetService.AssetView> items = hasMore
                ? List.copyOf(results.subList(0, pageSize))
                : List.copyOf(results);
        return new AssetPage(items, hasMore ? Integer.toString(offset + pageSize) : null);
    }

    @Transactional(readOnly = true)
    public DeleteImpact impact(List<UUID> assetIds) {
        List<UUID> ids = normalizeIds(assetIds);
        ActorContext actor = actorContextProvider.current();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(actor.tenantId());
        arguments.add(actor.userId());
        arguments.addAll(ids);
        DeleteImpact impact = jdbcTemplate.queryForObject("""
                with selected_asset as (
                    select id, blob_id
                    from asset
                    where tenant_id = ?
                      and user_id = ?
                      and deleted_at is null
                      and id in (%s)
                ),
                asset_reference as (
                    select relation.asset_id, run.task_id, run.id as run_id
                    from task_run_asset relation
                    join task_run run on run.id = relation.run_id
                    union all
                    select relation.asset_id, artifact.task_id, artifact.run_id
                    from artifact_asset relation
                    join artifact on artifact.id = relation.artifact_id
                )
                select
                    (select count(*) from selected_asset) as asset_count,
                    (
                        select coalesce(sum(blob.size_bytes), 0)
                        from asset_blob blob
                        where exists (
                            select 1
                            from selected_asset selected
                            where selected.blob_id = blob.id
                        )
                          and not exists (
                            select 1
                            from asset retained
                            where retained.blob_id = blob.id
                              and retained.deleted_at is null
                              and not exists (
                                select 1
                                from selected_asset selected
                                where selected.id = retained.id
                              )
                        )
                    ) as total_bytes,
                    count(distinct asset_reference.task_id) as affected_task_count,
                    count(distinct asset_reference.run_id) as affected_run_count
                from selected_asset
                left join asset_reference on asset_reference.asset_id = selected_asset.id
                """.formatted(placeholders),
                (resultSet, rowNumber) -> new DeleteImpact(
                        resultSet.getInt("asset_count"),
                        resultSet.getLong("total_bytes"),
                        resultSet.getInt("affected_task_count"),
                        resultSet.getInt("affected_run_count")
                ),
                arguments.toArray()
        );
        if (impact == null || impact.assetCount() != ids.size()) {
            throw new NotFoundException("asset selection", ids.size());
        }
        return impact;
    }

    @Transactional
    public DeleteResult delete(List<UUID> assetIds) {
        List<UUID> ids = normalizeIds(assetIds);
        DeleteImpact impact = impact(ids);
        ids.forEach(assetService::deleteOwned);
        return new DeleteResult(ids, impact);
    }

    private AssetService.AssetView mapAsset(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AssetService.AssetView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("sha256"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getString("origin"),
                resultSet.getString("media_category"),
                resultSet.getString("status"),
                resultSet.getTimestamp("deleted_at") == null
                        && !"DELETED".equals(resultSet.getString("status")),
                resultSet.getLong("associated_task_count"),
                resultSet.getString("latest_task_title")
        );
    }

    private static List<UUID> normalizeIds(List<UUID> assetIds) {
        List<UUID> ids = assetIds == null ? List.of() : assetIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            throw new PlatformException("ASSET_SELECTION_EMPTY", "Select at least one file");
        }
        if (ids.size() > MAX_SELECTION_SIZE) {
            throw new PlatformException(
                    "ASSET_SELECTION_TOO_LARGE",
                    "At most 1000 files can be deleted at once"
            );
        }
        return ids;
    }

    private static AssetOrigin parseLibraryType(String value) {
        if (value == null || value.isBlank() || "USER_FILE".equalsIgnoreCase(value)) {
            return AssetOrigin.USER_UPLOAD;
        }
        if ("MODEL_ASSET".equalsIgnoreCase(value)) return AssetOrigin.MODEL_OUTPUT;
        throw new PlatformException("ASSET_LIBRARY_TYPE_INVALID", "Unknown asset library type");
    }

    private static AssetMediaCategory parseCategory(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        try {
            return AssetMediaCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("ASSET_CATEGORY_INVALID", "Unknown asset category");
        }
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException exception) {
            throw new PlatformException("ASSET_CURSOR_INVALID", "Asset page cursor is invalid");
        }
    }

    public record AssetPage(List<AssetService.AssetView> items, String nextCursor) {
    }

    public record DeleteImpact(
            int assetCount,
            long totalBytes,
            int affectedTaskCount,
            int affectedRunCount
    ) {
    }

    public record DeleteResult(List<UUID> deletedAssetIds, DeleteImpact impact) {
    }
}
