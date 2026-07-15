package com.aibox.platform.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job")
public class JobEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false, length = 80)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_by", length = 160)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = 2_000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobEntity() {
    }

    public JobEntity(UUID id, UUID tenantId, UUID runId, Instant now) {
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
        this.type = "FEATURE_EXECUTION";
        this.status = JobStatus.QUEUED;
        this.maxAttempts = 3;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }
}

