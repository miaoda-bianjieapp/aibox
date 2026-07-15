package com.aibox.platform.artifact;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "artifact")
public class ArtifactEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "parent_artifact_id")
    private UUID parentArtifactId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, length = 80)
    private String kind;

    @Column(nullable = false, length = 240)
    private String title;

    @Column(name = "mime_type", nullable = false, length = 160)
    private String mimeType;

    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> content;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
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
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.taskId = taskId;
        this.runId = runId;
        this.parentArtifactId = parentArtifactId;
        this.versionNumber = versionNumber;
        this.kind = kind;
        this.title = title;
        this.mimeType = mimeType;
        this.content = Map.copyOf(content);
        this.metadata = Map.copyOf(metadata);
        this.createdAt = createdAt;
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

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getParentArtifactId() {
        return parentArtifactId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Map<String, Object> getContent() {
        return Map.copyOf(content);
    }

    public Map<String, Object> getMetadata() {
        return Map.copyOf(metadata);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
