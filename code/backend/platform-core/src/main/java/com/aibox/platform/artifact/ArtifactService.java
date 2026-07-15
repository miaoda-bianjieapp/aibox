package com.aibox.platform.artifact;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.OutputAssetDraft;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.task.TaskEntity;
import com.aibox.platform.task.TaskRepository;
import com.aibox.platform.task.TaskRunEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ArtifactService {

    private final ArtifactRepository artifactRepository;
    private final TaskRepository taskRepository;
    private final AssetService assetService;
    private final JdbcTemplate jdbcTemplate;
    private final ActorContextProvider actorContextProvider;
    private final Clock clock;

    public ArtifactService(
            ArtifactRepository artifactRepository,
            TaskRepository taskRepository,
            AssetService assetService,
            JdbcTemplate jdbcTemplate,
            ActorContextProvider actorContextProvider,
            Clock clock
    ) {
        this.artifactRepository = artifactRepository;
        this.taskRepository = taskRepository;
        this.assetService = assetService;
        this.jdbcTemplate = jdbcTemplate;
        this.actorContextProvider = actorContextProvider;
        this.clock = clock;
    }

    public List<ArtifactEntity> saveRunArtifacts(TaskRunEntity run, List<ArtifactDraft> drafts) {
        Instant now = clock.instant();
        List<ArtifactEntity> saved = new ArrayList<>();
        TaskEntity task = taskRepository.findOwnedForUpdate(
                        run.getTaskId(), run.getTenantId(), run.getUserId())
                .orElseThrow(() -> new NotFoundException("task", run.getTaskId()));
        ArtifactEntity parent = run.getBaseArtifactId() == null
                ? null
                : requireForTask(
                        run.getBaseArtifactId(), run.getTaskId(), run.getTenantId(), run.getUserId()
                );
        int versionNumber = artifactRepository.findMaxVersionNumber(run.getTaskId()) + 1;
        for (ArtifactDraft draft : drafts) {
            UUID artifactId = UUID.randomUUID();
            MaterializedContent materialized = materializeOutputAssets(run, draft);
            ArtifactEntity artifact = new ArtifactEntity(
                    artifactId,
                    run.getTenantId(),
                    run.getUserId(),
                    run.getTaskId(),
                    run.getId(),
                    parent == null ? null : parent.getId(),
                    versionNumber,
                    draft.kind(),
                    draft.title(),
                    draft.mimeType(),
                    materialized.content(),
                    draft.metadata(),
                    now
            );
            saved.add(artifactRepository.saveAndFlush(artifact));
            materialized.assets().forEach(asset -> jdbcTemplate.update(
                    "insert into artifact_asset (artifact_id, asset_id, role, created_at) values (?, ?, ?, ?)",
                    artifactId,
                    asset.id(),
                    asset.role(),
                    java.sql.Timestamp.from(now)
            ));
        }
        if (!saved.isEmpty()) {
            task.selectArtifact(saved.get(saved.size() - 1).getId(), now);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public ArtifactEntity requireForTask(UUID artifactId, UUID taskId, UUID tenantId, UUID userId) {
        ArtifactEntity artifact = artifactRepository.findByIdAndTenantIdAndUserId(artifactId, tenantId, userId)
                .orElseThrow(() -> new NotFoundException("artifact", artifactId));
        if (!artifact.getTaskId().equals(taskId)) {
            throw new NotFoundException("artifact", artifactId);
        }
        return artifact;
    }

    @Transactional(readOnly = true)
    public ArtifactReference baseReference(TaskRunEntity run) {
        if (run.getBaseArtifactId() == null) return null;
        ArtifactEntity artifact = requireForTask(
                run.getBaseArtifactId(), run.getTaskId(), run.getTenantId(), run.getUserId()
        );
        return new ArtifactReference(
                artifact.getId(), artifact.getVersionNumber(), artifact.getKind(), artifact.getMimeType(),
                artifact.getContent(), artifact.getMetadata()
        );
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> listByTask(UUID taskId) {
        ActorContext actor = actorContextProvider.current();
        return artifactRepository
                .findByTaskIdAndTenantIdAndUserIdOrderByCreatedAtDesc(taskId, actor.tenantId(), actor.userId())
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> listByRun(UUID runId) {
        ActorContext actor = actorContextProvider.current();
        return artifactRepository
                .findByRunIdAndTenantIdAndUserIdOrderByCreatedAtAsc(runId, actor.tenantId(), actor.userId())
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ArtifactView get(UUID artifactId) {
        ActorContext actor = actorContextProvider.current();
        return artifactRepository.findByIdAndTenantIdAndUserId(artifactId, actor.tenantId(), actor.userId())
                .map(this::toView)
                .orElseThrow(() -> new NotFoundException("artifact", artifactId));
    }

    private ArtifactView toView(ArtifactEntity artifact) {
        return new ArtifactView(
                artifact.getId(),
                artifact.getTaskId(),
                artifact.getRunId(),
                artifact.getParentArtifactId(),
                artifact.getVersionNumber(),
                artifact.getKind(),
                artifact.getTitle(),
                artifact.getMimeType(),
                artifact.getContent(),
                artifact.getMetadata(),
                artifact.getCreatedAt()
        );
    }

    private MaterializedContent materializeOutputAssets(
            TaskRunEntity run,
            ArtifactDraft draft
    ) {
        if (draft.outputAssets().isEmpty()) {
            return new MaterializedContent(draft.content(), List.of());
        }
        Map<String, Object> content = new LinkedHashMap<>(draft.content());
        Map<String, List<UUID>> idsByField = new LinkedHashMap<>();
        List<MaterializedAsset> assets = new ArrayList<>();
        for (OutputAssetDraft output : draft.outputAssets()) {
            AssetService.AssetView asset = assetService.storeGenerated(
                    run.getTenantId(), run.getUserId(), output.fileName(), output.mediaType(), output.content()
            );
            idsByField.computeIfAbsent(output.contentField(), ignored -> new ArrayList<>()).add(asset.id());
            assets.add(new MaterializedAsset(asset.id(), output.contentField()));
        }
        idsByField.forEach((field, ids) -> content.put(
                field,
                ids.size() == 1 ? ids.get(0).toString() : ids.stream().map(UUID::toString).toList()
        ));
        return new MaterializedContent(Map.copyOf(content), List.copyOf(assets));
    }

    public record ArtifactView(
            UUID id,
            UUID taskId,
            UUID runId,
            UUID parentArtifactId,
            int versionNumber,
            String kind,
            String title,
            String mimeType,
            Map<String, Object> content,
            Map<String, Object> metadata,
            Instant createdAt
    ) {
    }

    private record MaterializedContent(Map<String, Object> content, List<MaterializedAsset> assets) {
    }

    private record MaterializedAsset(UUID id, String role) {
    }
}
