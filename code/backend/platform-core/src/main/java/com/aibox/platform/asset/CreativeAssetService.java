package com.aibox.platform.asset;

import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.common.PlatformException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import com.aibox.platform.project.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class CreativeAssetService {

    private final CreativeAssetRepository repository;
    private final AssetService assetService;
    private final ProjectService projectService;
    private final ActorContextProvider actorContextProvider;
    private final Clock clock;

    public CreativeAssetService(
            CreativeAssetRepository repository,
            AssetService assetService,
            ProjectService projectService,
            ActorContextProvider actorContextProvider,
            Clock clock
    ) {
        this.repository = repository;
        this.assetService = assetService;
        this.projectService = projectService;
        this.actorContextProvider = actorContextProvider;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CreativeAssetView> list(String scope, UUID projectId, String assetType) {
        ActorContext actor = actorContextProvider.current();
        CreativeAssetScope parsedScope = parseScope(scope, false);
        CreativeAssetType parsedType = parseType(assetType, false);
        return repository
                .findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                        actor.tenantId(), actor.userId()
                )
                .stream()
                .filter(item -> parsedScope == null || item.getScope() == parsedScope)
                .filter(item -> projectId == null || projectId.equals(item.getProjectId()))
                .filter(item -> parsedType == null || item.getAssetType() == parsedType)
                .map(this::toView)
                .toList();
    }

    @Transactional
    public CreativeAssetView create(CreateCreativeAsset command) {
        ActorContext actor = actorContextProvider.current();
        CreativeAssetScope scope = parseScope(command.scope(), true);
        CreativeAssetType type = parseType(command.assetType(), true);
        UUID projectId = validatedProject(scope, command.projectId());
        String name = requiredText(command.name(), 120, "资产名称");
        String description = limitedText(command.description(), 2_000, "资产描述");
        String personality = validatedPersonality(type, command.personality());
        CreativeAssetEntity existing = repository
                .findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
                        actor.tenantId(), actor.userId()
                )
                .stream()
                .filter(item -> item.getScope() == scope)
                .filter(item -> Objects.equals(item.getProjectId(), projectId))
                .filter(item -> item.getAssetType() == type)
                .filter(item -> item.getName().equals(name))
                .filter(item -> item.getDescription().equals(description))
                .filter(item -> item.getPersonality().equals(personality))
                .findFirst()
                .orElse(null);
        if (existing != null) return toView(existing);

        Instant now = clock.instant();
        CreativeAssetEntity entity = new CreativeAssetEntity(
                UUID.randomUUID(),
                actor.tenantId(),
                actor.userId(),
                projectId,
                scope,
                type,
                name,
                description,
                personality,
                now
        );
        return toView(repository.save(entity));
    }

    @Transactional
    public CreativeAssetView update(UUID id, UpdateCreativeAsset command) {
        CreativeAssetEntity entity = requireOwned(id);
        CreativeAssetScope scope = command.scope() == null
                ? entity.getScope()
                : parseScope(command.scope(), true);
        CreativeAssetType type = command.assetType() == null
                ? entity.getAssetType()
                : parseType(command.assetType(), true);
        UUID requestedProject = command.projectId() == null
                ? entity.getProjectId()
                : command.projectId();
        UUID projectId = validatedProject(scope, requestedProject);
        String name = command.name() == null
                ? entity.getName()
                : requiredText(command.name(), 120, "资产名称");
        String description = command.description() == null
                ? entity.getDescription()
                : limitedText(command.description(), 2_000, "资产描述");
        String personality = command.personality() == null
                ? entity.getPersonality()
                : validatedPersonality(type, command.personality());

        Instant now = clock.instant();
        entity.updateDetails(projectId, scope, type, name, description, personality, now);
        UUID primary = requireImage(command.currentPrimaryAssetId());
        UUID threeView = requireImage(command.currentThreeViewAssetId());
        UUID approvedPrimary = requireImage(command.approvedPrimaryAssetId());
        UUID approvedThreeView = requireImage(command.approvedThreeViewAssetId());
        entity.updateAssets(primary, threeView, approvedPrimary, approvedThreeView, now);
        if (Boolean.TRUE.equals(command.clearCurrentThreeViewAsset())) {
            entity.clearCurrentThreeViewAsset(now);
        }
        return toView(repository.save(entity));
    }

    @Transactional
    public void delete(UUID id) {
        CreativeAssetEntity entity = requireOwned(id);
        entity.delete(clock.instant());
        repository.save(entity);
    }

    private CreativeAssetEntity requireOwned(UUID id) {
        ActorContext actor = actorContextProvider.current();
        return repository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        id, actor.tenantId(), actor.userId()
                )
                .orElseThrow(() -> new NotFoundException("creative asset", id));
    }

    private UUID validatedProject(CreativeAssetScope scope, UUID projectId) {
        if (scope == CreativeAssetScope.GLOBAL) return null;
        if (projectId == null) {
            throw new PlatformException(
                    "CREATIVE_ASSET_PROJECT_REQUIRED",
                    "Project assets must belong to a project"
            );
        }
        projectService.requireOwned(projectId);
        return projectId;
    }

    private UUID requireImage(UUID assetId) {
        if (assetId == null) return null;
        AssetService.AssetView asset = assetService.get(assetId);
        if (!asset.available() || !"IMAGE".equals(asset.category())) {
            throw new PlatformException(
                    "CREATIVE_ASSET_IMAGE_REQUIRED",
                    "Creative asset outputs must be available images"
            );
        }
        return assetId;
    }

    private CreativeAssetView toView(CreativeAssetEntity entity) {
        return new CreativeAssetView(
                entity.getId(),
                entity.getProjectId(),
                entity.getScope().name(),
                entity.getAssetType().name(),
                entity.getName(),
                entity.getDescription(),
                entity.getPersonality(),
                entity.getCurrentPrimaryAssetId(),
                entity.getCurrentThreeViewAssetId(),
                entity.getApprovedPrimaryAssetId(),
                entity.getApprovedThreeViewAssetId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private static CreativeAssetScope parseScope(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) return null;
            throw new PlatformException("CREATIVE_ASSET_SCOPE_REQUIRED", "Asset scope is required");
        }
        try {
            return CreativeAssetScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("CREATIVE_ASSET_SCOPE_INVALID", "Asset scope is invalid");
        }
    }

    private static CreativeAssetType parseType(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (!required) return null;
            throw new PlatformException("CREATIVE_ASSET_TYPE_REQUIRED", "Asset type is required");
        }
        try {
            return CreativeAssetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("CREATIVE_ASSET_TYPE_INVALID", "Asset type is invalid");
        }
    }

    private static String validatedPersonality(CreativeAssetType type, String value) {
        String normalized = limitedText(value, 1_000, "角色性格");
        if (type == CreativeAssetType.CHARACTER && normalized.isBlank()) {
            throw new PlatformException(
                    "CREATIVE_ASSET_PERSONALITY_REQUIRED",
                    "Character personality is required"
            );
        }
        return type == CreativeAssetType.CHARACTER ? normalized : "";
    }

    private static String requiredText(String value, int maximum, String label) {
        String normalized = limitedText(value, maximum, label);
        if (normalized.isBlank()) {
            throw new PlatformException("CREATIVE_ASSET_VALUE_REQUIRED", label + "不能为空");
        }
        return normalized;
    }

    private static String limitedText(String value, int maximum, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new PlatformException("CREATIVE_ASSET_VALUE_TOO_LONG", label + "超过长度限制");
        }
        return normalized;
    }

    public record CreateCreativeAsset(
            UUID projectId,
            String scope,
            String assetType,
            String name,
            String description,
            String personality
    ) {
    }

    public record UpdateCreativeAsset(
            UUID projectId,
            String scope,
            String assetType,
            String name,
            String description,
            String personality,
            UUID currentPrimaryAssetId,
            UUID currentThreeViewAssetId,
            UUID approvedPrimaryAssetId,
            UUID approvedThreeViewAssetId,
            Boolean clearCurrentThreeViewAsset
    ) {
    }

    public record CreativeAssetView(
            UUID id,
            UUID projectId,
            String scope,
            String assetType,
            String name,
            String description,
            String personality,
            UUID currentPrimaryAssetId,
            UUID currentThreeViewAssetId,
            UUID approvedPrimaryAssetId,
            UUID approvedThreeViewAssetId,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
