package com.aibox.platform.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_blob")
public class AssetBlobEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(name = "storage_backend", nullable = false, length = 30)
    private String storageBackend;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AssetBlobEntity() {
    }

    public AssetBlobEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            String sha256,
            long sizeBytes,
            String storageKey,
            Instant createdAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.storageBackend = "LOCAL_FS";
        this.status = "READY";
        this.createdAt = createdAt;
    }

    public void delete(Instant now) {
        this.status = "DELETED";
        this.deletedAt = now;
    }

    public void restore() {
        this.status = "READY";
        this.deletedAt = null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getSha256() { return sha256; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public String getStatus() { return status; }
}
