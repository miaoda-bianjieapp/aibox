package com.aibox.platform.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feature_definition")
public class FeatureDefinitionEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, unique = true, length = 120)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FeatureStatus status;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "result_type", nullable = false, length = 80)
    private String resultType;

    @Column(name = "renderer_key", nullable = false, length = 80)
    private String rendererKey;

    @Column(name = "execution_mode", nullable = false, length = 30)
    private String executionMode;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeatureDefinitionEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public FeatureStatus getStatus() {
        return status;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public String getResultType() {
        return resultType;
    }

    public String getRendererKey() {
        return rendererKey;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}

