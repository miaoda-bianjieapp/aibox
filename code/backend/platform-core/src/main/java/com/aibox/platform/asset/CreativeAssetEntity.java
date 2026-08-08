package com.aibox.platform.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creative_asset")
public class CreativeAssetEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreativeAssetScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 30)
    private CreativeAssetType assetType;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2_000)
    private String description;

    @Column(nullable = false, length = 1_000)
    private String personality;

    @Column(name = "current_primary_asset_id")
    private UUID currentPrimaryAssetId;

    @Column(name = "current_three_view_asset_id")
    private UUID currentThreeViewAssetId;

    @Column(name = "approved_primary_asset_id")
    private UUID approvedPrimaryAssetId;

    @Column(name = "approved_three_view_asset_id")
    private UUID approvedThreeViewAssetId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected CreativeAssetEntity() {
    }

    public CreativeAssetEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID projectId,
            CreativeAssetScope scope,
            CreativeAssetType assetType,
            String name,
            String description,
            String personality,
            Instant now
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.projectId = projectId;
        this.scope = scope;
        this.assetType = assetType;
        this.name = name;
        this.description = description;
        this.personality = personality;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateDetails(
            UUID projectId,
            CreativeAssetScope scope,
            CreativeAssetType assetType,
            String name,
            String description,
            String personality,
            Instant now
    ) {
        this.projectId = projectId;
        this.scope = scope;
        this.assetType = assetType;
        this.name = name;
        this.description = description;
        this.personality = personality;
        this.updatedAt = now;
    }

    public void updateAssets(
            UUID primaryAssetId,
            UUID threeViewAssetId,
            UUID approvedPrimaryAssetId,
            UUID approvedThreeViewAssetId,
            Instant now
    ) {
        if (primaryAssetId != null) this.currentPrimaryAssetId = primaryAssetId;
        if (threeViewAssetId != null) this.currentThreeViewAssetId = threeViewAssetId;
        if (approvedPrimaryAssetId != null) this.approvedPrimaryAssetId = approvedPrimaryAssetId;
        if (approvedThreeViewAssetId != null) this.approvedThreeViewAssetId = approvedThreeViewAssetId;
        this.updatedAt = now;
    }

    public void clearCurrentThreeViewAsset(Instant now) {
        this.currentThreeViewAssetId = null;
        this.updatedAt = now;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public CreativeAssetScope getScope() {
        return scope;
    }

    public CreativeAssetType getAssetType() {
        return assetType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPersonality() {
        return personality;
    }

    public UUID getCurrentPrimaryAssetId() {
        return currentPrimaryAssetId;
    }

    public UUID getCurrentThreeViewAssetId() {
        return currentThreeViewAssetId;
    }

    public UUID getApprovedPrimaryAssetId() {
        return approvedPrimaryAssetId;
    }

    public UUID getApprovedThreeViewAssetId() {
        return approvedThreeViewAssetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
