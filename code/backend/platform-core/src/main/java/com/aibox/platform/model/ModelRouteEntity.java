package com.aibox.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_route")
public class ModelRouteEntity {
    @Id private UUID id;
    @Column(name = "model_alias", nullable = false, length = 120) private String modelAlias;
    @Column(nullable = false, length = 80) private String capability;
    @Column(name = "deployment_code", nullable = false, length = 120) private String deploymentCode;
    @Column(nullable = false) private int priority;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected ModelRouteEntity() {}
    public String getDeploymentCode() { return deploymentCode; }
    public int getPriority() { return priority; }
}

