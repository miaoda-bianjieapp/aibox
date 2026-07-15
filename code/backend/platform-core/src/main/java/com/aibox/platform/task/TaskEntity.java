package com.aibox.platform.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "feature_code", nullable = false, length = 120)
    private String featureCode;

    @Column(nullable = false, length = 240)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    @Column(name = "current_artifact_id")
    private UUID currentArtifactId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected TaskEntity() {
    }

    public TaskEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID projectId,
            String featureCode,
            String title,
            Instant now
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.projectId = projectId;
        this.featureCode = featureCode;
        this.title = title;
        this.status = TaskStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void selectArtifact(UUID artifactId, Instant now) {
        this.currentArtifactId = artifactId;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getFeatureCode() {
        return featureCode;
    }

    public String getTitle() {
        return title;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public UUID getCurrentArtifactId() {
        return currentArtifactId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
