package com.aibox.platform.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset")
public class AssetEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "media_type", nullable = false, length = 200)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AssetEntity() {
    }

    public AssetEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            String originalName,
            String mediaType,
            long sizeBytes,
            String storageKey,
            String sha256,
            Instant createdAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.originalName = originalName;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.status = "READY";
        this.createdAt = createdAt;
    }

    public void delete(Instant now) {
        this.status = "DELETED";
        this.deletedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getOriginalName() { return originalName; }
    public String getMediaType() { return mediaType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public String getSha256() { return sha256; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}

